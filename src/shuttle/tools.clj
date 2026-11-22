;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.tools
  "Tools as data, with malli schemas. `deps` turns tool definitions into the
   `:tools-list` and `:call-tool` that `shuttle.http` and `shuttle.core` take.

   A definition:

   ```clojure
   {:name        \"templates\"                       ; required
    :title       \"List templates\"
    :description \"…\"
    :input       [:map [:limit {:optional true} [:int {:min 1 :max 500}]]]
    :output      [:map [:templates [:vector :map]]]  ; optional
    :annotations {:readOnlyHint true}
    :timeout-ms  180000                               ; optional
    :on-timeout  (fn [ctx args] …)                    ; optional: stop the work a timed-out call left
    :extra-args  :allow                               ; optional: let undeclared arguments through
    :visible?    (fn [ctx] …)                         ; optional: offered to this caller?
    :handler     (fn [ctx args] …)}                   ; required
   ```

   - `:input` becomes the announced `inputSchema` (JSON Schema) and is checked
     before the handler runs: arguments that do not fit are a refusal naming
     what is wrong, and the handler never sees them. Arguments are decoded
     first (JSON strings to keywords, enums, UUIDs… as the schema says) and
     `{:default …}` fills an absent optional field. An argument the schema
     does not declare is refused too, with the names the tool accepts, so a
     misspelt name is corrected rather than ignored; the announced schema says
     `additionalProperties: false`. `:extra-args :allow` lets them through, as
     does a map schema that says `{:closed false}` itself.
   - `:output` becomes `outputSchema`; a map result is then also sent as
     `structuredContent`. A result that does not fit is a server fault: the
     tool broke its own promise.
   - A handler refuses by returning `(refuse \"why\" {:code \"…\"})`; it fails
     by throwing. The library's own refusals carry `:code` too:
     `invalid_arguments`, `unknown_tool`, `timeout`. A plain map with a truthy
     `:error` is also read as a refusal (the older convention), unless the
     tool has an `:output` schema and the map fits it, or the handler returns
     it through `answer`: then `:error` is just a field of the data.
   - `:timeout-ms`: a handler still running then is interrupted, the call is a
     refusal saying so, and `:on-timeout` is called to stop what the handler
     started (cancel a query, say). The handler runs on a thread named
     `shuttle-tool-N` with the caller's dynamic bindings. Interruption only stops a handler that
     blocks or checks for it: one that computes without pause runs to its end.
   - `:visible?` filters per caller; `tools/list` and `tools/call` use the
     same test, so a caller cannot call what it is not offered.

   Instead of a vector, `deps` also takes `(fn [ctx])` returning the
   definitions, for tool sets that differ per caller or change while running.
   What a schema compiles to is kept by the schema's value, so definitions
   built anew on every call cost a lookup, not a compilation."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]
            [malli.transform :as mt]
            [malli.util :as mu])
  (:import (java.util.concurrent Callable ExecutionException ExecutorService Executors Future TimeUnit
                                 TimeoutException)))

(defn refuse
  "A handler's refusal: `{:error message}` merged over `more` (add `:code` for
   programs to branch on), marked so that it is a refusal whatever else is true."
  ([message] (refuse message nil))
  ([message more] (vary-meta (assoc (or more {}) :error message) assoc :mcp/refusal true)))

(defn answer
  "Marks a handler's result as an answer, not a refusal, though it has an
   `:error` field. Not needed for a tool whose `:output` schema the result fits."
  [result]
  (vary-meta result assoc :mcp/answer true))

(def ^:private any-object {:type "object"})

(defn- json-schema [schema]
  (let [js (mjs/transform schema)]
    (if (= "object" (:type js)) js (merge js any-object))))

(defn- check-definition! [{:keys [name handler] :as t}]
  (when-not (and (string? name) (seq name)) (throw (ex-info "tool :name must be a non-empty string" {:tool t})))
  (when-not (fn? handler) (throw (ex-info (str "tool " name ": :handler must be a function") {})))
  t)

(defn- compile-schemas
  "Everything a tool's schemas compile to. Throws on an invalid schema."
  [input output extra-args]
  (let [in (when input (cond-> (m/schema input) (not= :allow extra-args) mu/closed-schema))
        out (when output (m/schema output))]
    {::in in
     ::accepts (when (and in (= :map (m/type in))) (mapv #(let [k (first %)] (if (keyword? k) (subs (str k) 1) (str k))) (m/children in)))
     ::decode (when in (m/decoder in (mt/transformer (mt/json-transformer) (mt/default-value-transformer {::mt/add-optional-keys true}))))
     ::valid-in? (when in (m/validator in))
     ::valid-out? (when out (m/validator out))
     ::input-schema (if in (json-schema in) any-object)
     ::output-schema (when out (json-schema out))}))

;; Compiled schemas by the schemas' value. Per-caller definitions are built on
;; every call; compiling each time cost milliseconds per call. Bounded: schemas
;; that never compare equal (they hold a fresh function) would grow it forever.
(defonce ^:private compiled (atom {}))
(def ^:private compiled-max 4096)

(defn- schemas-of [input output extra-args]
  (let [k [input output extra-args]]
    (or (get @compiled k)
        (let [v (compile-schemas input output extra-args)]
          (swap! compiled #(assoc (if (>= (count %) compiled-max) {} %) k v))
          v))))

(defn- compile-tool
  "Everything that does not depend on the call."
  [{:keys [name title description input output annotations extra-args] :as t}]
  (check-definition! t)
  (let [c (schemas-of input output extra-args)]
    (assoc (merge t c)
           ::listing (cond-> {:name name :inputSchema (::input-schema c)}
                       title (assoc :title title)
                       description (assoc :description description)
                       (::output-schema c) (assoc :outputSchema (::output-schema c))
                       annotations (assoc :annotations annotations)))))

;; Platform threads, not virtual ones: a handler that computes (parses a file)
;; would hold a carrier thread, and a few of those stall every virtual thread
;; in the JVM. Daemon threads of this library's own, so a handler left running
;; after its timeout is visible by name and never keeps the JVM alive.
(defonce ^:private runner
  (delay (let [n (java.util.concurrent.atomic.AtomicLong.)]
           (Executors/newCachedThreadPool
            (reify java.util.concurrent.ThreadFactory
              (newThread [_ r]
                (doto (Thread. r (str "shuttle-tool-" (.incrementAndGet n))) (.setDaemon true))))))))

(defn- run-with-timeout [ms f on-timeout]
  (let [^Future fut (.submit ^ExecutorService @runner ^Callable (bound-fn* f))]
    (try (.get fut (long ms) TimeUnit/MILLISECONDS)
         (catch TimeoutException _
           (.cancel fut true)
           (when on-timeout (try (on-timeout) (catch Throwable _ nil)))
           ::timed-out)
         (catch ExecutionException e (throw (or (.getCause e) e))))))

(defn- call [t ctx args]
  (let [args (if-let [dec (::decode t)] (dec args) args)]
    (if (and (::valid-in? t) (not ((::valid-in? t) args)))
      (refuse "invalid arguments" (cond-> {:code "invalid_arguments" :details (me/humanize (m/explain (::in t) args))}
                                    (::accepts t) (assoc :accepts (::accepts t))))
      (let [run #((:handler t) ctx args)
            result (if-let [ms (:timeout-ms t)]
                     (run-with-timeout ms run (when-let [f (:on-timeout t)] #(f ctx args)))
                     (run))
            marks (meta result)]
        (cond
          (= ::timed-out result) (refuse (str "the tool did not finish within " (:timeout-ms t) " ms") {:code "timeout"})
          (:mcp/refusal marks) result
          ;; a result that fits :output is an answer, :error field or not
          (and (::valid-out? t) ((::valid-out? t) result))
          (if (map? result) (vary-meta result assoc :mcp/structured-content true :mcp/answer true) result)
          (:mcp/answer marks) (if (::valid-out? t)
                                (throw (ex-info (str "tool " (:name t) " returned a result outside its :output schema") {}))
                                result)
          ;; the older convention: a plain map with :error refuses
          (and (map? result) (contains? result :error)) result
          (::valid-out? t) (throw (ex-info (str "tool " (:name t) " returned a result outside its :output schema") {}))
          :else result)))))

(defn- unique! [ts]
  (when-not (or (empty? ts) (apply distinct? (map :name ts)))
    (throw (ex-info "tool names must be unique" {:names (map :name ts)})))
  ts)

(defn deps
  "`{:tools-list … :call-tool …}` for a vector of tool definitions, or for
   `(fn [ctx])` returning them. Merge it into the deps given to `shuttle.http`."
  [defs]
  (let [fixed (when-not (fn? defs) (unique! (mapv compile-tool defs)))
        all (if fixed (constantly fixed) #(unique! (mapv compile-tool (defs %))))
        visible (fn [ctx] (filter #(if-let [v (:visible? %)] (v ctx) true) (all ctx)))]
    {:tools-list (fn [ctx] (mapv ::listing (visible ctx)))
     :call-tool (fn [ctx tool args]
                  (if-let [t (some #(when (= tool (:name %)) %) (visible ctx))]
                    (call t ctx args)
                    (refuse (str "unknown tool: " tool) {:code "unknown_tool"})))}))

(defn data-deps
  "`deps` for `shuttle.http/data-routes`: the same, but every tool must have an
   `:output` schema (a program reads the result, so its shape is a contract)
   and a `:timeout-ms` (a program must know how long it may wait). Per-caller
   definition functions are checked on each call."
  [defs]
  (let [check (fn [ts]
                (doseq [t ts]
                  (when-not (:output t) (throw (ex-info (str "data tool " (:name t) " needs :output") {})))
                  (when-not (pos-int? (:timeout-ms t)) (throw (ex-info (str "data tool " (:name t) " needs :timeout-ms") {}))))
                ts)]
    (deps (if (fn? defs) (comp check defs) (check defs)))))
