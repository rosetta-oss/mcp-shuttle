;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.client-test
  "The client against this library's own server (round trips) and against a scripted server
   (every way an answer can go wrong). Both run on the JDK's HttpServer on a free port."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shuttle.client :as client]
            [shuttle.http :as mcp]
            [shuttle.json :as json])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)))

;; ---------------------------------------------------------------------------
;; A Ring-ish adapter over the JDK server
;; ---------------------------------------------------------------------------

(defn- ring-request [^HttpExchange ex]
  {:request-method (keyword (str/lower-case (.getRequestMethod ex)))
   :uri (.getPath (.getRequestURI ex))
   :query-string (.getQuery (.getRequestURI ex))
   :headers (into {} (for [[k vs] (.getRequestHeaders ex)] [(str/lower-case k) (first vs)]))
   :body (.getRequestBody ex)})

(defn- respond! [^HttpExchange ex {:keys [status headers body]}]
  (doseq [[k v] headers] (.add (.getResponseHeaders ex) (str k) (str v)))
  (let [b (.getBytes (str body) "UTF-8")]
    (.sendResponseHeaders ex (or status 404) (if (seq b) (count b) -1))
    (with-open [o (.getResponseBody ex)] (.write o b))))

(defn- serve
  "Starts a server whose every request goes to (handler ring-request) → Ring response;
   returns [url stop-fn]."
  [handler]
  (let [srv (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/" (reify HttpHandler
                              (handle [_ ex]
                                (try (respond! ex (or (handler (ring-request ex)) {:status 404}))
                                     (catch Throwable _ (.close ex))))))
    (.setExecutor srv (java.util.concurrent.Executors/newCachedThreadPool))
    (.start srv)
    [(str "http://127.0.0.1:" (.getPort (.getAddress srv)) "/mcp") #(.stop srv 0)]))

(defmacro ^:private with-server [[url handler] & body]
  `(let [[~url stop#] (serve ~handler)]
     (try ~@body (finally (stop#)))))

;; ---------------------------------------------------------------------------
;; Round trips against shuttle.http
;; ---------------------------------------------------------------------------

(def ^:private real
  (mcp/routes {:authenticate (fn [r] (when (= "k" (mcp/bearer r)) {:id "c1" :ctx {}}))
               :tools-list (fn [_] [{:name "echo" :description "echoes"}])
               :call-tool (fn [_ tool args]
                            (case tool
                              "echo" {:said (:text args)}
                              "boom" (throw (ex-info "secret detail" {}))
                              {:error (str "no tool " tool)}))
               :server-info {:name "t" :version "1"}
               :unauthorized-message "needs a key"
               :on-fault (fn [& _])
               :sse-writer identity}))

(deftest round-trip
  (with-server [url real]
    (testing "one-shot call"
      (let [res (client/call-tool {:url url :bearer "k"} "echo" {:text "hi"})]
        (is (= {:said "hi"} (json/read (client/text res))))))
    (testing "a connection: initialize once, list, call twice"
      (let [c (client/connect {:url url :bearer "k"})]
        (is (= ["echo"] (map :name (client/list-tools c))))
        (is (= "1" (get-in (client/initialize! c) [:serverInfo :version])))
        (is (some? (client/call-tool c "echo" {:text "a"})))
        (is (some? (client/call-tool c "echo" {:text "b"})))
        (client/close c)))
    (testing "a refusal is isError → exception with the tool's text"
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (client/call-tool {:url url :bearer "k" :label "Svc"} "nope" {})))]
        ;; the server sends a refusal's whole map, as JSON, as the text
        (is (= "Svc nope: {\"error\":\"no tool nope\"}" (ex-message e)))
        (is (true? (get-in (ex-data e) [:result :isError])))))
    (testing "a fault reaches the client without its detail"
      (let [e (is (thrown? clojure.lang.ExceptionInfo (client/call-tool {:url url :bearer "k"} "boom" {})))]
        (is (not (str/includes? (ex-message e) "secret detail")))))
    (testing "a wrong key is a 401 and the message carries no key"
      (let [e (is (thrown? clojure.lang.ExceptionInfo (client/call-tool {:url url :bearer "wrong-key"} "echo" {})))]
        (is (= "MCP 401 on initialize" (ex-message e)))
        (is (= {:status 401 :code -32001} (ex-data e)))
        (is (not (str/includes? (pr-str (ex-data e)) "wrong-key")))))))

;; ---------------------------------------------------------------------------
;; A scripted server: initialize is fine, tools/call answers what the test says
;; ---------------------------------------------------------------------------

(defn- scripted
  "handler for a server whose tools/call answer is (on-call id request) → Ring response."
  [on-call & {:keys [session]}]
  (fn [{:keys [request-method body] :as req}]
    (when (= :post request-method)
      (let [m (json/read (slurp body))
            ok (fn [result] {:status 200 :headers {"Content-Type" "application/json"}
                             :body (json/write {:jsonrpc "2.0" :id (:id m) :result result})})]
        (case (:method m)
          "initialize" (cond-> (ok {:protocolVersion "2025-06-18" :capabilities {:tools {}}
                                    :serverInfo {:name "s" :version "1"}})
                         session (assoc-in [:headers "Mcp-Session-Id"] (session)))
          "notifications/initialized" {:status 202}
          "tools/call" (on-call (:id m) req)
          {:status 400})))))

(defn- result-json [id result]
  (json/write {:jsonrpc "2.0" :id id :result result}))

(deftest abnormal-answers
  (testing "JSON-RPC error"
    (with-server [url (scripted (fn [id _] {:status 200 :headers {"Content-Type" "application/json"}
                                            :body (json/write {:jsonrpc "2.0" :id id :error {:code -32602 :message "bad args"}})}))]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (client/call-tool {:url url} "t" {})))]
        (is (= "MCP t: bad args" (ex-message e)))
        (is (= {:code -32602} (ex-data e))))))
  (testing "500 with a plain-text body"
    (with-server [url (scripted (fn [_ _] {:status 500 :headers {"Content-Type" "text/plain"} :body "boom"}))]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (client/call-tool {:url url} "t" {})))]
        (is (= "MCP 500 on t" (ex-message e)))
        (is (= {:status 500} (ex-data e))))))
  (testing "a body declared JSON that is not JSON fails at once, not at the timeout"
    (with-server [url (scripted (fn [_ _] {:status 200 :headers {"Content-Type" "application/json"} :body "{oops"}))]
      (let [t0 (System/currentTimeMillis)
            e (is (thrown? clojure.lang.ExceptionInfo (client/call-tool {:url url :timeout-ms 30000} "t" {})))]
        (is (= "MCP t: unreadable answer" (ex-message e)))
        (is (< (- (System/currentTimeMillis) t0) 5000)))))
  (testing "a tool's text that is not JSON is still a result"
    (with-server [url (scripted (fn [id _] {:status 200 :headers {"Content-Type" "application/json"}
                                            :body (result-json id {:content [{:type "text" :text "plain"}]})}))]
      (is (= "plain" (client/text (client/call-tool {:url url} "t" {}))))))
  (testing "no answer in time"
    (with-server [url (scripted (fn [_ _] (Thread/sleep 3000) {:status 500}))]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (client/call-tool {:url url :timeout-ms 500} "slow" {})))]
        (is (= "MCP timed out on slow" (ex-message e))))))
  (testing "an event-stream answer: notifications skipped, the matching response taken"
    (with-server [url (scripted (fn [id _]
                                  {:status 200 :headers {"Content-Type" "text/event-stream"}
                                   :body (str "event: message\ndata: "
                                              (json/write {:jsonrpc "2.0" :method "notifications/progress" :params {:progress 1}})
                                              "\n\n"
                                              "event: message\ndata: " (result-json id {:content [{:type "text" :text "{\"n\":1}"}]})
                                              "\n\n")}))]
      (is (= {:n 1} (json/read (client/text (client/call-tool {:url url} "t" {}))))))))

(deftest session-handling
  (testing "a session id is sent back, and a 404 means: initialize again, once"
    (let [sessions (atom 0)
          seen (atom [])
          calls (atom 0)]
      (with-server [url (scripted (fn [id {:keys [headers]}]
                                    (swap! seen conj (get headers "mcp-session-id"))
                                    (if (= 1 (swap! calls inc))
                                      {:status 404}
                                      {:status 200 :headers {"Content-Type" "application/json"}
                                       :body (result-json id {:content [{:type "text" :text "ok"}]})}))
                                  :session #(str "s" (swap! sessions inc)))]
        (is (= "ok" (client/text (client/call-tool {:url url} "t" {}))))
        (is (= ["s1" "s2"] @seen))
        (is (= 2 @sessions)))))
  (testing "a second 404 is an error, not a loop"
    (with-server [url (scripted (fn [_ _] {:status 404}) :session (constantly "s"))]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (client/call-tool {:url url} "t" {})))]
        (is (= "MCP 404 on t" (ex-message e)))))))
