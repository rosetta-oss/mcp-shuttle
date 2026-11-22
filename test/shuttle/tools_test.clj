;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.tools-test
  "Tool definitions with malli schemas, driven through `shuttle.core/handle`
   with no HTTP at all."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shuttle.core :as core]
            [shuttle.json :as json]
            [shuttle.tools :as tools]))

(def ^:private defs
  [{:name "add"
    :title "Add"
    :description "Adds two numbers"
    :input [:map [:a :int] [:b {:optional true :default 0} :int]]
    :output [:map [:sum :int]]
    :annotations {:readOnlyHint true}
    :handler (fn [_ {:keys [a b]}] {:sum (+ a b)})}
   {:name "color"
    :input [:map [:c [:enum :red :green]]]
    :handler (fn [_ {:keys [c]}] (str "got " (name c)))}
   {:name "liar"
    :output [:map [:n :int]]
    :handler (fn [_ _] {:n "not a number"})}
   {:name "refuse"
    :handler (fn [_ _] {:error "not today" :why "closed"})}
   {:name "slow"
    :timeout-ms 200
    :handler (fn [_ _] (Thread/sleep 2000) "late")}
   {:name "admin"
    :visible? (fn [ctx] (:admin ctx))
    :handler (fn [_ _] "secret")}])

(defn- deps [] (merge (tools/deps defs) {:server-info {:name "t" :version "1"} :on-fault (fn [& _])}))

(defn- ask
  ([method params] (ask method params {} "2025-06-18"))
  ([method params ctx version]
   (json/read (core/handle (deps) {:jsonrpc "2.0" :id 7 :method method :params params} ctx version))))

(defn- call [tool args & [ctx]] (:result (ask "tools/call" {:name tool :arguments args} (or ctx {}) "2025-06-18")))

(deftest listing
  (let [tools (:tools (:result (ask "tools/list" nil)))
        add (first (filter #(= "add" (:name %)) tools))]
    (is (= ["add" "color" "liar" "refuse" "slow"] (map :name tools)) "admin is hidden from this caller")
    (is (= "object" (get-in add [:inputSchema :type])))
    (is (= ["a"] (map name (get-in add [:inputSchema :required]))))
    (is (= "integer" (get-in add [:inputSchema :properties :a :type])))
    (is (= "object" (get-in add [:outputSchema :type])))
    (is (= {:readOnlyHint true} (:annotations add)))
    (testing "a tool without :input still announces an object schema"
      (is (= {:type "object"} (:inputSchema (first (filter #(= "refuse" (:name %)) tools))))))
    (testing "an older revision does not see title / outputSchema"
      (let [old (first (:tools (:result (ask "tools/list" nil {} "2025-03-26"))))]
        (is (nil? (:title old)))
        (is (nil? (:outputSchema old)))))
    (testing "the admin sees the admin tool"
      (is (some #{"admin"} (map :name (:tools (:result (ask "tools/list" nil {:admin true} "2025-06-18")))))))))

(deftest calling
  (testing "valid arguments, structured output"
    (let [r (call "add" {:a 2 :b 3})]
      (is (= {:sum 5} (:structuredContent r)))
      (is (= {:sum 5} (json/read (get-in r [:content 0 :text]))))))
  (testing "an absent optional field gets its :default"
    (is (= {:sum 2} (:structuredContent (call "add" {:a 2})))))
  (testing "arguments are decoded as the schema says (JSON string → keyword enum)"
    (is (= "got red" (get-in (call "color" {:c "red"}) [:content 0 :text]))))
  (testing "invalid arguments are a refusal naming the field; the handler never runs"
    (let [r (call "add" {:a "two"})
          body (json/read (get-in r [:content 0 :text]))]
      (is (true? (:isError r)))
      (is (= "invalid arguments" (:error body)))
      (is (contains? (:details body) :a))))
  (testing "missing required field"
    (is (true? (:isError (call "add" {})))))
  (testing "a refusal keeps its whole finding, as JSON"
    (let [r (call "refuse" {})]
      (is (true? (:isError r)))
      (is (= {:error "not today" :why "closed"} (json/read (get-in r [:content 0 :text]))))))
  (testing "a result outside :output is a fault, with no detail"
    (let [r (call "liar" {})]
      (is (true? (:isError r)))
      (is (str/includes? (get-in r [:content 0 :text]) "reference"))
      (is (not (str/includes? (get-in r [:content 0 :text]) "outside")))))
  (testing "a tool past its timeout is abandoned"
    (let [t0 (System/currentTimeMillis)
          r (call "slow" {})]
      (is (true? (:isError r)))
      (is (str/includes? (get-in r [:content 0 :text]) "200 ms"))
      (is (< (- (System/currentTimeMillis) t0) 1500))))
  (testing "a hidden tool cannot be called either"
    (is (true? (:isError (call "admin" {}))))
    (is (= "secret" (get-in (call "admin" {} {:admin true}) [:content 0 :text]))))
  (testing "an unknown tool"
    (is (true? (:isError (call "nope" {}))))))

(deftest per-caller-definitions
  (let [d (merge (tools/deps (fn [ctx] [{:name (str "hello-" (:who ctx)) :handler (fn [_ _] "hi")}]))
                 {:server-info {:name "t" :version "1"}})
        names #(map :name (get-in (json/read (core/handle d {:jsonrpc "2.0" :id 1 :method "tools/list"} {:who %} "2025-06-18"))
                                  [:result :tools]))]
    (is (= ["hello-ann"] (names "ann")))
    (is (= ["hello-bob"] (names "bob")))))

(deftest bad-definitions
  (is (thrown? Exception (tools/deps [{:name "x"}])) "no handler")
  (is (thrown? Exception (tools/deps [{:name "x" :handler identity :input [:nonsense]}])))
  (is (thrown? Exception (tools/deps [{:name "x" :handler identity} {:name "x" :handler identity}]))))

(deftest core-without-transport
  (testing "initialize negotiates and a notification has no reply"
    (is (= "2025-06-18" (get-in (ask "initialize" {:protocolVersion "2025-06-18"}) [:result :protocolVersion])))
    (is (= "2025-06-18" (get-in (ask "initialize" {:protocolVersion "1999-01-01"}) [:result :protocolVersion])))
    (is (nil? (core/handle (deps) {:jsonrpc "2.0" :method "notifications/initialized"} {} "2025-06-18")))))

(def ^:dynamic *who* nil)

(deftest undeclared-arguments
  (let [d (fn [t] (merge (tools/deps [t]) {:server-info {:name "t" :version "1"}}))
        run (fn [t args] (:result (json/read (core/handle (d t) {:jsonrpc "2.0" :id 1 :method "tools/call" :params {:name "t" :arguments args}} {} "2025-06-18"))))
        t {:name "t" :input [:map [:limit {:optional true} :int]] :handler (fn [_ args] {:got args})}]
    (testing "a misspelt name is refused, with the names the tool accepts"
      (let [r (run t {:limt 5})
            body (json/read (get-in r [:content 0 :text]))]
        (is (true? (:isError r)))
        (is (= "invalid_arguments" (:code body)))
        (is (contains? (:details body) :limt))
        (is (= ["limit"] (:accepts body)))))
    (testing "and the announced schema says so"
      (is (false? (get-in (first ((:tools-list (d t)) {})) [:inputSchema :additionalProperties]))))
    (testing ":extra-args :allow lets them through"
      (is (= {:got {:limt 5}} (json/read (get-in (run (assoc t :extra-args :allow) {:limt 5}) [:content 0 :text])))))
    (testing "a schema that says {:closed false} itself is left open"
      (is (nil? (:isError (run (assoc t :input [:map {:closed false} [:limit {:optional true} :int]]) {:limt 5})))))))

(deftest refusals-and-answers
  (let [run (fn [t] (let [d (merge (tools/deps [(assoc t :name "t")]) {:server-info {:name "t" :version "1"}})]
                      (:result (json/read (core/handle d {:jsonrpc "2.0" :id 1 :method "tools/call" :params {:name "t"}} {} "2025-06-18")))))
        text #(json/read (get-in % [:content 0 :text]))]
    (testing "refuse marks a refusal"
      (let [r (run {:handler (fn [_ _] (tools/refuse "closed" {:code "ledger_closed"}))})]
        (is (true? (:isError r)))
        (is (= {:error "closed" :code "ledger_closed"} (text r)))))
    (testing "a refusal is one even from a tool with :output"
      (is (true? (:isError (run {:output [:map [:n :int]] :handler (fn [_ _] (tools/refuse "no"))})))))
    (testing "a result that fits :output is an answer though it has an :error field"
      (let [r (run {:output [:map [:rows :int] [:error :string]] :handler (fn [_ _] {:rows 3 :error "a column of the data"})})]
        (is (nil? (:isError r)))
        (is (= {:rows 3 :error "a column of the data"} (:structuredContent r)))))
    (testing "answer does the same for a tool without :output"
      (is (nil? (:isError (run {:handler (fn [_ _] (tools/answer {:rows 3 :error "data"}))})))))
    (testing "an unmarked map with :error is still a refusal"
      (is (true? (:isError (run {:handler (fn [_ _] {:error "old style"})})))))))

(deftest timeouts
  (let [stopped (promise)
        run (fn [t] (let [d (merge (tools/deps [(assoc t :name "t")]) {:server-info {:name "t" :version "1"}})]
                      (:result (json/read (core/handle d {:jsonrpc "2.0" :id 1 :method "tools/call" :params {:name "t" :arguments {}}} {:who "ann"} "2025-06-18")))))]
    (testing ":on-timeout is told, with the call's ctx and arguments"
      (let [r (run {:timeout-ms 100 :handler (fn [_ _] (Thread/sleep 5000) "late")
                    :on-timeout (fn [ctx args] (deliver stopped [ctx args]))})]
        (is (true? (:isError r)))
        (is (= [{:who "ann"} {}] (deref stopped 1000 :never)))))
    (testing "a blocked handler is interrupted"
      (let [interrupted (promise)]
        (run {:timeout-ms 100 :handler (fn [_ _] (try (Thread/sleep 5000) (catch InterruptedException _ (deliver interrupted true))))})
        (is (true? (deref interrupted 1000 false)))))
    (testing "the handler sees the caller's dynamic bindings"
      (is (= "bound" (get-in (binding [*who* "bound"] (run {:timeout-ms 1000 :handler (fn [_ _] *who*)})) [:content 0 :text]))))))

(deftest per-caller-definitions-compile-once
  (let [compiles (atom 0)
        real @#'tools/compile-schemas
        fresh (fn [_] [{:name "a" :input [:map [:n :int] [:unique-to-this-test :string]] :output [:map [:n :int]]
                        :handler (fn [_ {:keys [n]}] {:n n})}])]
    (with-redefs [tools/compile-schemas (fn [& xs] (swap! compiles inc) (apply real xs))]
      (let [d (tools/deps fresh)]
        (dotimes [_ 50]
          ((:tools-list d) {})
          (is (= {:n 1} ((:call-tool d) {} "a" {:n 1 :unique-to-this-test "x"}))))
        (is (= 1 @compiles) "definitions built anew on every call, compiled once")))
    (testing "duplicate names from a definitions function are refused too"
      (is (thrown? Exception ((:tools-list (tools/deps (fn [_] [{:name "x" :handler identity} {:name "x" :handler identity}]))) {}))))))

(deftest tool-names
  (doseq [bad ["has space" "slash/name" "" (apply str (repeat 129 "a")) "naïve"]]
    (is (thrown? Exception (tools/deps [{:name bad :handler identity}])) bad))
  (doseq [good ["a" "list_orders" "orders.list" "get-order-2" (apply str (repeat 128 "a"))]]
    (is (some? (tools/deps [{:name good :handler identity}])) good)))

(deftest content-blocks
  (let [run (fn [t] (let [d (merge (tools/deps [(assoc t :name "t")]) {:server-info {:name "t" :version "1"}})]
                      (:result (json/read (core/handle d {:jsonrpc "2.0" :id 1 :method "tools/call" :params {:name "t"}} {} "2025-06-18")))))
        blocks [{:type "text" :text "a chart"} {:type "image" :data "aGk=" :mimeType "image/png"}]]
    (is (= {:content blocks} (run {:handler (fn [_ _] (tools/content blocks))})))
    (testing "with more, and past an :output schema"
      (is (= {:content blocks :_meta {:took 3} :structuredContent {:n 1}}
             (run {:output [:map [:n :int]]
                   :handler (fn [_ _] (tools/content blocks {:_meta {:took 3} :structuredContent {:n 1}}))}))))))
