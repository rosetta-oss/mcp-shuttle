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
    (is (= "2025-03-26" (get-in (ask "initialize" {:protocolVersion "1999-01-01"}) [:result :protocolVersion])))
    (is (nil? (core/handle (deps) {:jsonrpc "2.0" :method "notifications/initialized"} {} "2025-06-18")))))
