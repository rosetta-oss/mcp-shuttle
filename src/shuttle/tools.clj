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
    :visible?    (fn [ctx] …)                         ; optional: offered to this caller?
    :handler     (fn [ctx args] …)}                   ; required
   ```

   - `:input` becomes the announced `inputSchema` (JSON Schema) and is checked
     before the handler runs: arguments that do not fit are a refusal naming
     what is wrong, and the handler never sees them. Arguments are decoded
     first (JSON strings to keywords, enums, UUIDs… as the schema says) and
     `{:default …}` fills an absent optional field.
   - `:output` becomes `outputSchema`; a map result is then also sent as
     `structuredContent`. A result that does not fit is a server fault: the
     tool broke its own promise.
   - A handler refuses by returning `{:error …}` (add `:code` for programs to
     branch on); it fails by throwing. The library's own refusals carry
     `:code` too: `invalid_arguments`, `unknown_tool`, `timeout`.
   - `:timeout-ms`: a handler still running then is abandoned and the call is
     a refusal saying so.
   - `:visible?` filters per caller; `tools/list` and `tools/call` use the
     same test, so a caller cannot call what it is not offered.

   Instead of a vector, `deps` also takes `(fn [ctx])` returning the
   definitions, for tool sets that differ per caller or change while running."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]
            [malli.transform :as mt])
  (:import (java.util.concurrent ExecutionException Future TimeUnit TimeoutException)))

(def ^:private any-object {:type "object"})

(defn- json-schema [schema]
  (let [js (mjs/transform schema)]
    (if (= "object" (:type js)) js (merge js any-object))))

(defn- check-definition! [{:keys [name handler input output] :as t}]
  (when-not (and (string? name) (seq name)) (throw (ex-info "tool :name must be a non-empty string" {:tool t})))
  (when-not (fn? handler) (throw (ex-info (str "tool " name ": :handler must be a function") {})))
  (doseq [[k s] [[:input input] [:output output]] :when s]
    (m/schema s))                                           ; throws on an invalid schema
  t)

(defn- compile-tool
  "Everything that does not depend on the call, worked out once."
  [{:keys [name title description input output annotations] :as t}]
  (check-definition! t)
  (let [in (when input (m/schema input))
        out (when output (m/schema output))]
    (assoc t
           ::in in
           ::decode (when in (m/decoder in (mt/transformer (mt/json-transformer) (mt/default-value-transformer {::mt/add-optional-keys true}))))
           ::valid-in? (when in (m/validator in))
           ::valid-out? (when out (m/validator out))
           ::listing (cond-> {:name name
                              :inputSchema (if in (json-schema in) any-object)}
                       title (assoc :title title)
                       description (assoc :description description)
                       out (assoc :outputSchema (json-schema out))
                       annotations (assoc :annotations annotations)))))

(defn- run-with-timeout [ms f]
  (let [^Future fut (future (f))]
    (try (.get fut (long ms) TimeUnit/MILLISECONDS)
         (catch TimeoutException _ (.cancel fut true) ::timed-out)
         (catch ExecutionException e (throw (or (.getCause e) e))))))

(defn- call [t ctx args]
  (let [args (if-let [dec (::decode t)] (dec args) args)]
    (if (and (::valid-in? t) (not ((::valid-in? t) args)))
      {:error "invalid arguments" :code "invalid_arguments" :details (me/humanize (m/explain (::in t) args))}
      (let [run #((:handler t) ctx args)
            result (if-let [ms (:timeout-ms t)] (run-with-timeout ms run) (run))]
        (cond
          (= ::timed-out result) {:error (str "the tool did not finish within " (:timeout-ms t) " ms") :code "timeout"}
          (and (map? result) (contains? result :error)) result
          (::valid-out? t)
          (if ((::valid-out? t) result)
            (if (map? result) (vary-meta result assoc :mcp/structured-content true) result)
            (throw (ex-info (str "tool " (:name t) " returned a result outside its :output schema") {})))
          :else result)))))

(defn deps
  "`{:tools-list … :call-tool …}` for a vector of tool definitions, or for
   `(fn [ctx])` returning them. Merge it into the deps given to `shuttle.http`."
  [defs]
  (let [fixed (when-not (fn? defs)
                (let [ts (mapv compile-tool defs)]
                  (when-not (apply distinct? (map :name ts))
                    (throw (ex-info "tool names must be unique" {:names (map :name ts)})))
                  ts))
        ;; per-caller definitions are compiled per call; a host with many such calls can memoize its fn
        all (if fixed (constantly fixed) #(mapv compile-tool (defs %)))
        visible (fn [ctx] (filter #(if-let [v (:visible? %)] (v ctx) true) (all ctx)))]
    {:tools-list (fn [ctx] (mapv ::listing (visible ctx)))
     :call-tool (fn [ctx tool args]
                  (if-let [t (some #(when (= tool (:name %)) %) (visible ctx))]
                    (call t ctx args)
                    {:error (str "unknown tool: " tool) :code "unknown_tool"}))}))

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
