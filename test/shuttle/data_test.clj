;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.data-test
  "The data profile: one POST route, tools only, limits required, stable codes."
  (:require [clojure.test :refer [deftest is testing]]
            [shuttle.http :as mcp]
            [shuttle.json :as json]
            [shuttle.tools :as tools]))

(def ^:private defs
  [{:name "sum"
    :input [:map [:xs [:vector :int]]]
    :output [:map [:sum :int]]
    :timeout-ms 1000
    :handler (fn [_ {:keys [xs]}] {:sum (reduce + xs)})}
   {:name "closed"
    :output [:map]
    :timeout-ms 1000
    :handler (fn [_ _] {:error "the ledger is closed" :code "ledger_closed"})}
   {:name "boom"
    :output [:map]
    :timeout-ms 1000
    :handler (fn [_ _] (throw (ex-info "jdbc://secret-host" {})))}])

(defn- handler [& [over]]
  (mcp/data-routes (merge (tools/data-deps defs)
                          {:authenticate (fn [r] (when (= "k" (mcp/bearer r)) {:id "svc" :ctx {}}))
                           :server-info {:name "d" :version "1"}
                           :unauthorized-message "needs a service key"
                           :instructions "should not be announced"
                           :resources-list (fn [_] [{:uri "x"}])
                           :rate-limit? (mcp/rate-limiter 100 60000)
                           :max-body-bytes 10000
                           :on-fault (fn [& _])}
                          over)))

(defn- post [h body & {:keys [uri] :or {uri "/mcp/data"}}]
  (h {:request-method :post :uri uri
      :headers {"authorization" "Bearer k" "mcp-protocol-version" "2025-06-18"}
      :body (java.io.ByteArrayInputStream. (.getBytes (json/write body) "UTF-8"))}))

(defn- rpc [method & [params]] (cond-> {:jsonrpc "2.0" :id 1 :method method} params (assoc :params params)))
(defn- result [resp] (:result (json/read (:body resp))))
(defn- tool-json [resp] (json/read (get-in (result resp) [:content 0 :text])))

(deftest routes-and-capabilities
  (let [h (handler)]
    (is (nil? (h {:request-method :post :uri "/mcp"})) "only its own path")
    (is (= 405 (:status (h {:request-method :get :uri "/mcp/data"}))))
    (is (nil? (h {:request-method :get :uri "/mcp/sse"})) "no SSE transport")
    (let [init (result (post h (rpc "initialize" {:protocolVersion "2025-06-18"})))]
      (is (= #{:tools} (set (keys (:capabilities init)))))
      (is (nil? (:instructions init))))
    (is (= -32601 (get-in (json/read (:body (post h (rpc "resources/list")))) [:error :code])))
    (is (= 202 (:status (post h {:jsonrpc "2.0" :method "notifications/initialized"}))))
    (testing "a custom path"
      (is (some? ((handler {:path "/mcp/v1"}) {:request-method :get :uri "/mcp/v1"}))))))

(deftest stable-codes
  (let [h (handler)]
    (is (= {:sum 6} (:structuredContent (result (post h (rpc "tools/call" {:name "sum" :arguments {:xs [1 2 3]}}))))))
    (is (= "invalid_arguments" (:code (tool-json (post h (rpc "tools/call" {:name "sum" :arguments {:xs "no"}}))))))
    (is (= "unknown_tool" (:code (tool-json (post h (rpc "tools/call" {:name "nope" :arguments {}}))))))
    (is (= "ledger_closed" (:code (tool-json (post h (rpc "tools/call" {:name "closed" :arguments {}}))))))
    (let [fault (tool-json (post h (rpc "tools/call" {:name "boom" :arguments {}})))]
      (is (= "internal_error" (:code fault)))
      (is (string? (:reference fault)))
      (is (not (re-find #"secret" (pr-str fault)))))))

(deftest limits-are-required-and-work
  (is (thrown? Exception (mcp/data-routes (dissoc (tools/data-deps defs) :rate-limit?))))
  (is (thrown? Exception (mcp/data-routes (merge (tools/data-deps defs) {:rate-limit? (constantly {:allowed? true})}))))
  (let [h (handler {:rate-limit? (mcp/rate-limiter 2 60000)})]
    (is (= 200 (:status (post h (rpc "ping")))))
    (is (= 200 (:status (post h (rpc "ping")))))
    (let [r (post h (rpc "ping"))]
      (is (= 429 (:status r)))
      (is (pos? (parse-long (get-in r [:headers "Retry-After"]))))))
  (is (= 413 (:status (post (handler {:max-body-bytes 50}) (rpc "tools/call" {:name "sum" :arguments {:xs (vec (range 100))}}))))))

(deftest data-tools-need-output-and-timeout
  (is (thrown? Exception (tools/data-deps [{:name "x" :timeout-ms 10 :handler identity}])))
  (is (thrown? Exception (tools/data-deps [{:name "x" :output [:map] :handler identity}]))))
