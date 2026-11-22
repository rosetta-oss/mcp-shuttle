;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.http-test
  "The wire's own tests.

   **Read the fake below before anything else**: it authenticates the string
   \"good\" and serves two tools called `echo` and `boom`. There is no graph,
   no workspace, no token store and no model anywhere in this file — and that
   is an assertion, not an accident. `the-wire-knows-no-business` at the
   bottom enforces it over the module's source, because the whole reason this
   library exists is that every host injects its own judgement into it."
  (:require [shuttle.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shuttle.http :as mcp]))

;; ---------------------------------------------------------------------------
;; A fake host. Three injected functions and nothing else.
;; ---------------------------------------------------------------------------

(def ^:private good "good")

(defn- deps
  ([] (deps {}))
  ([over]
   (merge {:authenticate (fn [request]
                           (when (= good (mcp/bearer request))
                             ;; id is opaque and NOT the credential —
                             ;; it outlives the request in the SSE session map.
                             {:id "caller-1" :ctx {:caps #{:read}}}))
           :tools-list (fn [ctx]
                         ;; Takes ctx on purpose: capability filtering is the
                         ;; host's, and `tools/list` must be the SAME decision
                         ;; dispatch makes.
                         (cond-> [{:name "echo" :description "echoes"}]
                           (contains? (:caps ctx) :write)
                           (conj {:name "write_thing" :description "writes"})))
           :call-tool (fn [_ctx tool args]
                        (case tool
                          "echo"  {:said (:text args)}
                          "boom"  (throw (ex-info "kaboom" {}))
                          {:error "no such tool" :name tool}))
           :server-info {:name "test-mcp" :version "1"}
           :instructions "READ ME FIRST"
           :unauthorized-message "Unauthorized — this endpoint needs a key"
           :sse-writer identity}
          over)))

(defn- post
  ([d body] (post d body {}))
  ([d body {:keys [auth uri] :or {auth good uri "/mcp"}}]
   ((mcp/routes d)
    {:request-method :post :uri uri
     :headers (cond-> {"host" "127.0.0.1:9632"}
                auth (assoc "authorization" (str "Bearer " auth)))
     :body (java.io.ByteArrayInputStream. (.getBytes (json/write body) "UTF-8"))})))

(defn- rpc [method & [params id]]
  (cond-> {:jsonrpc "2.0" :method method :id (or id 1)}
    params (assoc :params params)))

(defn- body-of [response] (json/read (:body response)))

;; ---------------------------------------------------------------------------

(deftest the-credential-is-consumed-before-any-parsing
  "A refused caller must not reach the JSON-RPC parser at all: parsing first
   would mean an unauthenticated body had already chosen a code path."
  (testing "no bearer at all"
    (is (= 401 (:status (post (deps) (rpc "initialize") {:auth nil})))))
  (testing "a bearer this host does not accept"
    (is (= 401 (:status (post (deps) (rpc "initialize") {:auth "forged"})))))
  (testing "the 401's sentence is the host's, not ours"
    (is (= "Unauthorized — this endpoint needs a key"
           (get-in (body-of (post (deps) (rpc "initialize") {:auth nil})) [:error :message]))
        "a generic message would teach the caller to look for the wrong key"))
  (testing "even a body that is not JSON at all still 401s, not 400"
    (let [r ((mcp/routes (deps))
             {:request-method :post :uri "/mcp"
              :headers {"host" "127.0.0.1:9632"}
              :body (java.io.ByteArrayInputStream. (.getBytes "{[[[" "UTF-8"))})]
      (is (= 401 (:status r))))))

(deftest initialize-negotiates-the-protocol-version
  "The spec's rule: echo a version we speak, otherwise answer with the newest
   one we speak — never fail the handshake over it."
  (testing "a version we speak comes back unchanged"
    (is (= "2024-11-05"
           (get-in (body-of (post (deps) (rpc "initialize" {:protocolVersion "2024-11-05"})))
                   [:result :protocolVersion]))))
  (testing "one we do not speak, older or newer, gets the newest we do, not an error"
    (doseq [asked ["1999-01-01" "2025-11-25" "2099-01-01"]]
      (let [r (body-of (post (deps) (rpc "initialize" {:protocolVersion asked})))]
        (is (= "2025-06-18" (get-in r [:result :protocolVersion])) asked)
        (is (nil? (:error r))))))
  (testing "serverInfo and instructions are the host's"
    (let [r (body-of (post (deps) (rpc "initialize")))]
      (is (= {:name "test-mcp" :version "1"} (get-in r [:result :serverInfo])))
      (is (= "READ ME FIRST" (get-in r [:result :instructions])))))
  (testing "a host with nothing to say sends no instructions key"
    (let [r (body-of (post (deps {:instructions nil}) (rpc "initialize")))]
      (is (not (contains? (:result r) :instructions))
          "absent and empty are different facts about a server"))))

(deftest tools-list-is-computed-from-the-caller-context
  "The load-bearing property of injecting `:tools-list` as a function OF ctx:
   `tools/list` and dispatch must be one capability decision. A host that can
   only announce a fixed vector would have to filter dispatch separately, and
   the two would disagree the day one of them was edited."
  (let [read-only (set (map :name (get-in (body-of (post (deps) (rpc "tools/list")))
                                          [:result :tools])))
        writer (set (map :name (get-in (body-of (post (deps {:authenticate
                                                             (fn [_] {:id "c2" :ctx {:caps #{:read :write}}})})
                                                       (rpc "tools/list")))
                                       [:result :tools])))]
    (is (= #{"echo"} read-only))
    (is (= #{"echo" "write_thing"} writer))))

(deftest tools-list-is-byte-stable
  "A client diffs this. Two calls that differ by ordering would read as an
   interface change on every poll."
  (is (= (:body (post (deps) (rpc "tools/list")))
         (:body (post (deps) (rpc "tools/list"))))))

(deftest a-refusal-is-an-answer-a-throw-is-a-fault
  "The distinction the whole surface turns on, and the reason `:call-tool`'s
   contract distinguishes a returned `{:error …}` from a thrown exception. The
   first is something the agent must READ and act on; the second is the host's
   bug, and pretending otherwise invites the agent to retry it forever."
  (testing "a returned :error becomes an isError result carrying the finding"
    (let [r (body-of (post (deps) (rpc "tools/call" {:name "nope" :arguments {}})))]
      (is (nil? (:error r)) "a refusal is NOT a JSON-RPC protocol error")
      (is (true? (get-in r [:result :isError])))
      (is (str/includes? (get-in r [:result :content 0 :text]) "no such tool"))))
  (testing "a thrown exception is reported as a fault, not as the tool's answer"
    (let [r (body-of (post (deps) (rpc "tools/call" {:name "boom" :arguments {}})))]
      (is (true? (get-in r [:result :isError])))
      (is (str/includes? (get-in r [:result :content 0 :text]) "the tool failed"))))
  (testing "a thrown exception's text never reaches the client"
    (let [logged (atom nil)
          d (deps {:on-fault (fn [ref e tool] (reset! logged [ref (ex-message e) tool]))
                   :call-tool (fn [_ _ _]
                                (throw (java.sql.SQLException.
                                        "ERROR: relation \"hr.salaries\" does not exist; jdbc:postgresql://db.internal:5432/payroll user=svc_reader")))})
          r (body-of (post d (rpc "tools/call" {:name "boom" :arguments {}})))
          text (get-in r [:result :content 0 :text])]
      (is (true? (get-in r [:result :isError])))
      (is (not (re-find #"salaries|jdbc|db\.internal|svc_reader|payroll" text)) text)
      (is (str/includes? text "internal server error"))
      (testing "the full text is logged under the reference the client sees"
        (is (str/includes? (second @logged) "jdbc:postgresql"))
        (is (str/includes? text (str "reference " (first @logged))))
        (is (= "boom" (nth @logged 2))))))
  (testing "a success carries the tool's value"
    (let [r (body-of (post (deps) (rpc "tools/call" {:name "echo" :arguments {:text "hi"}})))]
      (is (nil? (get-in r [:result :isError])))
      (is (str/includes? (get-in r [:result :content 0 :text]) "hi")))))

(deftest an-unknown-method-is-a-protocol-error
  (is (= -32601 (get-in (body-of (post (deps) (rpc "tools/nope"))) [:error :code]))))

(deftest a-notification-gets-no-body
  "`notifications/initialized` has no id and expects no reply — answering it
   with a JSON-RPC response is a protocol violation some clients hard-fail on."
  (is (= 202 (:status (post (deps) {:jsonrpc "2.0" :method "notifications/initialized"})))))

(deftest malformed-messages-map-to-the-right-codes
  (testing "a body that is not JSON is -32700"
    (let [r ((mcp/routes (deps))
             {:request-method :post :uri "/mcp"
              :headers {"host" "127.0.0.1:9632" "authorization" (str "Bearer " good)}
              :body (java.io.ByteArrayInputStream. (.getBytes "{not json" "UTF-8"))})]
      (is (= 400 (:status r)))
      (is (= -32700 (get-in (body-of r) [:error :code])))))
  (testing "valid JSON that is not a Request is -32600, echoing its id"
    (let [r (body-of (post (deps) {:id 7 :method "ping"}))]
      (is (= -32600 (get-in r [:error :code])))
      (is (= 7 (:id r))))))

(deftest a-body-cap-refuses-instead-of-truncating
  (let [big (rpc "tools/call" {:name "echo" :arguments {:text (apply str (repeat 500 "x"))}})]
    (testing "no cap -> no limit, as before"
      (is (= 200 (:status (post (deps) big)))))
    (testing "over the cap -> 413 naming the cap, and nothing is dispatched"
      (let [called (atom false)
            r (post (deps {:max-body-bytes 100 :call-tool (fn [& _] (reset! called true) {})}) big)]
        (is (= 413 (:status r)))
        (is (= -32600 (get-in (body-of r) [:error :code])))
        (is (re-find #"100 bytes" (get-in (body-of r) [:error :message])))
        (is (not @called))))
    (testing "under the cap -> served"
      (is (= 200 (:status (post (deps {:max-body-bytes 10000}) big)))))
    (testing "the plain /mcp/messages leg has the same cap"
      (let [d (deps {:max-body-bytes 100})
            stream ((mcp/routes d)
                    {:request-method :get :uri "/mcp/sse"
                     :headers {"host" "127.0.0.1:9632" "authorization" (str "Bearer " good)}})
            out (java.io.ByteArrayOutputStream.)
            t (doto (Thread. #((:body stream) out)) (.setDaemon true) (.start))
            _ (Thread/sleep 120)
            sid (second (re-find #"sessionId=([0-9a-f-]{36})" (str out)))
            r ((mcp/routes d)
               {:request-method :post :uri "/mcp/messages" :query-string (str "sessionId=" sid)
                :headers {"host" "127.0.0.1:9632" "authorization" (str "Bearer " good)}
                :body (java.io.ByteArrayInputStream. (.getBytes (json/write big) "UTF-8"))})]
        (.interrupt t)
        (is (some? sid))
        (is (= 413 (:status r)))
        (is (re-find #"100 bytes" (get-in (body-of r) [:error :message])))))))

(deftest a-batch-is-dispatched-elementwise
  "JSON-RPC 2.0 batching: auth is checked once, each element dispatched on the
   same path, notifications contribute no response."
  (let [r (post (deps) [(rpc "ping" nil 1)
                        {:jsonrpc "2.0" :method "notifications/initialized"}
                        (rpc "ping" nil 2)])
        parsed (json/read (:body r))]
    (is (= 200 (:status r)))
    (is (= [1 2] (mapv :id parsed)) "two replies, the notification silent"))
  (testing "a batch of only notifications gets 202, not an empty array"
    (is (= 202 (:status (post (deps) [{:jsonrpc "2.0" :method "notifications/initialized"}])))))
  (testing "an empty batch is itself an Invalid Request"
    (is (= -32600 (get-in (body-of (post (deps) [])) [:error :code])))))

(deftest a-cross-origin-browser-request-is-refused
  "The spec's DNS-rebinding guard. Non-browser clients send no Origin and pass
   untouched; a page on another origin does not."
  (let [r ((mcp/routes (deps))
           {:request-method :post :uri "/mcp"
            :headers {"host" "127.0.0.1:9632"
                      "origin" "https://evil.example"
                      "authorization" (str "Bearer " good)}
            :body (java.io.ByteArrayInputStream. (.getBytes "{}" "UTF-8"))})]
    (is (= 403 (:status r))))
  (testing "the guard runs BEFORE auth — a forbidden origin never reaches it"
    (let [called (atom false)
          r ((mcp/routes (deps {:authenticate (fn [_] (reset! called true) nil)}))
             {:request-method :post :uri "/mcp"
              :headers {"host" "127.0.0.1:9632" "origin" "https://evil.example"}
              :body (java.io.ByteArrayInputStream. (.getBytes "{}" "UTF-8"))})]
      (is (= 403 (:status r)))
      (is (false? @called)))))

(deftest get-mcp-says-post-instead
  "This server opens no server-initiated stream, so the Streamable HTTP spec
   wants a 405 naming the method that does work."
  (let [r ((mcp/routes (deps)) {:request-method :get :uri "/mcp" :headers {}})]
    (is (= 405 (:status r)))
    (is (= "POST" (get-in r [:headers "Allow"])))))

(deftest the-rate-limiter-is-keyed-on-the-opaque-id
  "Absent means no limiting (a visible config fact). Present, it sees the id
   the authenticator returned and nothing else — the transport has no other
   notion of who a caller is."
  (let [seen (atom nil)
        d (deps {:rate-limit? (fn [id] (reset! seen id) {:allowed? false :retry-after-secs 3})})
        r (post d (rpc "ping"))]
    (is (= 429 (:status r)))
    (is (= "3" (get-in r [:headers "Retry-After"])))
    (is (= "caller-1" @seen))))

;; ---------------------------------------------------------------------------
;; The SSE leg (2024-11-05)
;; ---------------------------------------------------------------------------

(defn- sse-get
  ([d] (sse-get d good))
  ([d auth]
   ((mcp/routes d)
    {:request-method :get :uri "/mcp/sse"
     :headers (cond-> {"host" "127.0.0.1:9632"}
                auth (assoc "authorization" (str "Bearer " auth)))})))

(defn- drain
  "Run the streamed body against a byte sink for a moment, then interrupt it —
   the loop is infinite by design (it parks on the queue), so a test reads the
   handshake and stops."
  [response]
  (let [out (java.io.ByteArrayOutputStream.)
        t (doto (Thread. #((:body response) out)) (.setDaemon true) (.start))]
    (Thread/sleep 120)
    (.interrupt t)
    (str out)))

(deftest the-sse-handshake-announces-this-sessions-endpoint
  "The first event IS the handshake: a client that does not learn where to
   POST cannot use this transport at all."
  (let [r (sse-get (deps))]
    (is (= 200 (:status r)))
    (is (= "text/event-stream" (get-in r [:headers "Content-Type"])))
    (let [text (drain r)]
      (is (str/includes? text "event: endpoint"))
      (is (re-find #"data: /mcp/messages\?sessionId=[0-9a-f-]{36}" text)))))

(deftest an-unauthenticated-stream-is-refused
  (is (= 401 (:status (sse-get (deps) nil)))))

(deftest messages-need-a-session-that-exists-and-is-yours
  (testing "no session at all"
    (let [r (post (deps) (rpc "ping") {:uri "/mcp/messages"})]
      (is (= 404 (:status r)))
      (is (= -32002 (get-in (body-of r) [:error :code])))))
  (testing "a made-up session id"
    (let [r ((mcp/routes (deps))
             {:request-method :post :uri "/mcp/messages"
              :query-string "sessionId=00000000-0000-0000-0000-000000000000"
              :headers {"host" "127.0.0.1:9632" "authorization" (str "Bearer " good)}
              :body (java.io.ByteArrayInputStream.
                     (.getBytes (json/write (rpc "ping")) "UTF-8"))})]
      (is (= 404 (:status r))))))

(deftest a-message-reaches-the-stream-that-owns-it
  "The round trip the plain transport is: POST here, read there. Also pins
   that a FOREIGN caller gets the same 404 an unknown session gets —
   distinguishing them would tell an attacker which sessions exist."
  (let [d (deps)
        stream (sse-get d)
        out (java.io.ByteArrayOutputStream.)
        t (doto (Thread. #((:body stream) out)) (.setDaemon true) (.start))
        _ (Thread/sleep 120)
        sid (second (re-find #"sessionId=([0-9a-f-]{36})" (str out)))
        send (fn [dd] ((mcp/routes dd)
                       {:request-method :post :uri "/mcp/messages"
                        :query-string (str "sessionId=" sid)
                        :headers {"host" "127.0.0.1:9632"
                                  "authorization" (str "Bearer " good)}
                        :body (java.io.ByteArrayInputStream.
                               (.getBytes (json/write (rpc "ping" nil 9)) "UTF-8"))}))]
    (is (some? sid) "the handshake must have announced a session id")
    (testing "the owner's message is accepted and pushed onto the stream"
      (is (= 202 (:status (send d))))
      (Thread/sleep 120)
      (is (str/includes? (str out) "\"id\":9")))
    (testing "another caller's message on the same session is a 404, not a push"
      (let [foreign (deps {:authenticate (fn [_] {:id "someone-else" :ctx {:caps #{:read}}})})]
        (is (= 404 (:status (send foreign))))))
    (.interrupt t)))

;; ---------------------------------------------------------------------------
;; What each protocol revision is told
;; ---------------------------------------------------------------------------

(defn- post-at
  "One POST carrying `MCP-Protocol-Version: version` (nil: no header)."
  [d body version]
  ((mcp/routes d)
   {:request-method :post :uri "/mcp"
    :headers (cond-> {"host" "127.0.0.1:9632" "authorization" (str "Bearer " good)}
               version (assoc "mcp-protocol-version" version))
    :body (java.io.ByteArrayInputStream. (.getBytes (json/write body) "UTF-8"))}))

(def ^:private rich-deps
  (deps {:tools-list (fn [_] [{"name" "echo" "description" "echoes" "title" "Echo"
                                "annotations" {"readOnlyHint" true}
                                "outputSchema" {"type" "object"}}])
         :call-tool (fn [_ _ args]
                      (with-meta {:said (:text args)} {:mcp/structured-content true}))
         :server-info {:name "test-mcp" :version "1" :title "A titled server"}}))

(deftest each-revision-gets-only-the-fields-it-defines
  (let [tool #(first (get-in (body-of (post-at rich-deps (rpc "tools/list") %)) [:result :tools]))]
    (testing "2025-06-18: title, annotations and outputSchema"
      (is (= #{:name :description :title :annotations :outputSchema} (set (keys (tool "2025-06-18"))))))
    (testing "no header means 2025-03-26 (spec): annotations, nothing newer"
      (is (= #{:name :description :annotations} (set (keys (tool nil)))))
      (is (= (tool nil) (tool "2025-03-26"))))
    (testing "2024-11-05 knew none of them"
      (is (= #{:name :description} (set (keys (tool "2024-11-05")))))))
  (testing "an unknown header is read as the assumed revision, never refused"
    (is (= 200 (:status (post-at rich-deps (rpc "tools/list") "2099-01-01"))))))

(deftest structured-content-rides-beside-the-text
  (let [call #(get (body-of (post-at rich-deps (rpc "tools/call" {:name "echo" :arguments {:text "hi"}}) %))
                   :result)]
    (testing "2025-06-18: the same object twice — text first, unchanged, then structured"
      (let [r (call "2025-06-18")]
        (is (= {:said "hi"} (:structuredContent r)))
        (is (= {:said "hi"} (json/read (get-in r [:content 0 :text]))))))
    (testing "older revisions: text only"
      (is (not (contains? (call nil) :structuredContent)))
      (is (not (contains? (call "2024-11-05") :structuredContent)))))
  (testing "an unmarked result never gets structured content"
    (is (not (contains? (:result (body-of (post-at (deps) (rpc "tools/call" {:name "echo" :arguments {:text "x"}})
                                                   "2025-06-18")))
                        :structuredContent))))
  (testing "a refusal never does either"
    (let [r (:result (body-of (post-at (deps {:call-tool (fn [_ _ _] (with-meta {:error "no"} {:mcp/structured-content true}))})
                                       (rpc "tools/call" {:name "x"}) "2025-06-18")))]
      (is (true? (:isError r)))
      (is (not (contains? r :structuredContent))))))

(deftest server-info-may-depend-on-the-caller
  (let [d (deps {:server-info (fn [ctx] {:name "test-mcp" :version "2" :title (str (:caps ctx))})
                 :instructions (fn [ctx] (str "caps " (:caps ctx)))})
        r (:result (body-of (post d (rpc "initialize" {:protocolVersion "2025-06-18"}))))]
    (is (= {:name "test-mcp" :version "2" :title "#{:read}"} (:serverInfo r)))
    (is (= "caps #{:read}" (:instructions r))))
  (testing "serverInfo.title is 2025-06-18's: an older client is not sent it"
    (is (= {:name "test-mcp" :version "1"}
           (get-in (body-of (post rich-deps (rpc "initialize" {:protocolVersion "2025-03-26"})))
                   [:result :serverInfo])))))

(deftest resources-and-prompts-exist-only-where-a-host-serves-them
  (testing "absent: no capability, and the methods are unknown"
    (is (= #{:tools} (set (keys (get-in (body-of (post (deps) (rpc "initialize"))) [:result :capabilities])))))
    (doseq [m ["resources/list" "resources/read" "prompts/list" "prompts/get"]]
      (is (= -32601 (get-in (body-of (post (deps) (rpc m {}))) [:error :code])) m)))
  (testing "present: computed from the caller's ctx"
    (let [d (deps {:resources-list (fn [ctx] [{:uri "x://a" :name (str (:caps ctx))}])
                   :resource-read (fn [_ uri] (when (= uri "x://a") {:contents [{:uri uri :text "A"}]}))
                   :prompts-list (fn [_] [{:name "p"}])
                   :prompt-get (fn [_ n _] (if (= n "p") {:messages []} {:error "no such prompt"}))})]
      (is (= #{:tools :resources :prompts}
             (set (keys (get-in (body-of (post d (rpc "initialize"))) [:result :capabilities])))))
      (is (= "#{:read}" (get-in (body-of (post d (rpc "resources/list"))) [:result :resources 0 :name])))
      (is (= "A" (get-in (body-of (post d (rpc "resources/read" {:uri "x://a"}))) [:result :contents 0 :text])))
      (is (= -32002 (get-in (body-of (post d (rpc "resources/read" {:uri "x://b"}))) [:error :code])))
      (is (= [] (get-in (body-of (post d (rpc "prompts/get" {:name "p"}))) [:result :messages])))
      (is (= -32602 (get-in (body-of (post d (rpc "prompts/get" {:name "q"}))) [:error :code]))))))

;; ---------------------------------------------------------------------------
;; The library knows no host
;; ---------------------------------------------------------------------------

(deftest the-wire-knows-no-business
  "**The plan's exit condition, as a test rather than a promise.**

   This module carries the protocol and nothing else. If a graph, a workspace,
   a token store or a model concept appears in its source, then the judgement
   did not really get injected out, and every other host would inherit it.

   `token` is checked as a word, not a substring: `:token-id` would be a
   business concept leaking back in, while a comment about the fact that
   credentials are consumed early is fine, so the words that ARE allowed are
   spelled out below."
  (let [;; Read via the CLASSPATH, not a relative directory: this test must
        ;; give the same verdict whether it runs from the module or from the
        ;; root aggregate, and a path that silently resolves to nothing would
        ;; make the gate pass by reading zero files.
        paths ["shuttle/http.clj"
               "shuttle/json.clj"
               "shuttle/core.clj"
               "shuttle/tools.clj"
               "shuttle/client.clj"
               "shuttle/impl/transport.clj"
               "shuttle/impl/jsonrpc.clj"]
        sources (for [p paths
                      :let [r (io/resource p)]]
                  [p (some-> r slurp)])
        _ (doseq [[p text] sources]
            (is (some? text) (str "not on the classpath: " p)))
        forbidden [#"(?i)\bgraph\b" #"(?i)\bworkspace\b" #"(?i)\bdialect\b"
                   #"(?i)\bresolver\b" #"(?i)\bentity\b" #"(?i)\bEQL\b"]
        hits (for [[path text] sources
                   pattern forbidden
                   :let [found (re-seq pattern text)]
                   :when (seq found)]
               (str path " mentions " pattern))]
    (is (= (count paths) (count sources)) "every source file must have been read")
    (is (empty? hits)
        (str "the wire must not know these: " (pr-str (vec hits))))
    (testing "and it requires nothing outside the library and its deps"
      (is (empty? (for [[path text] sources
                        :let [req (re-find #"\(:require[\s\S]*?\)\)" text)]
                        ns (re-seq #"\[([a-z][\w.\-]*) " (or req ""))
                        :when (not (re-matches #"(shuttle|clojure|java|malli)\..*" (second ns)))]
                    [path (second ns)]))))))

;; ---------------------------------------------------------------------------
;; What is never answered, and what never escapes
;; ---------------------------------------------------------------------------

(deftest notifications-and-responses-get-no-reply
  "A message without an id is a notification and a notification is never
   answered, whatever its method: a client that cancels a request must not be
   sent an error for saying so."
  (doseq [m [{:jsonrpc "2.0" :method "notifications/cancelled" :params {:requestId 1}}
             {:jsonrpc "2.0" :method "notifications/progress" :params {:progress 1}}
             {:jsonrpc "2.0" :method "notifications/made-up"}
             ;; a request method sent as a notification is not run either
             {:jsonrpc "2.0" :method "tools/call" :params {:name "boom"}}
             ;; a response to a request this server never sent
             {:jsonrpc "2.0" :id 5 :result {}}
             {:jsonrpc "2.0" :id 5 :error {:code -1 :message "x"}}]]
    (let [r (post (deps) m)]
      (is (= 202 (:status r)) (pr-str m))
      (is (str/blank? (str (:body r))) (pr-str m))))
  (testing "in a batch they contribute nothing"
    (let [r (post (deps) [{:jsonrpc "2.0" :method "notifications/cancelled"} (rpc "ping" nil 9)])]
      (is (= [9] (map :id (json/read (:body r)))))))
  (testing "an object that is neither request, notification nor response is still Invalid Request"
    (is (= -32600 (get-in (body-of (post (deps) {:jsonrpc "2.0" :id 1})) [:error :code])))
    (is (= -32600 (get-in (body-of (post (deps) {:jsonrpc "2.0" :id 1 :method 7})) [:error :code])))))

(deftest nothing-thrown-reaches-the-host
  "Whatever throws, the handler returns a response: the host's HTTP stack never
   gets to print the Throwable, and the client is told a reference and nothing else."
  (let [seen (atom [])
        d (fn [over] (deps (merge {:on-fault (fn [reference e what] (swap! seen conj [reference (ex-message e) what]))} over)))
        secret "jdbc://secret-host/db"
        clean? (fn [r] (not (str/includes? (str (:body r)) "secret")))]
    (testing "the host's :tools-list throws"
      (reset! seen [])
      (let [r (post (d {:tools-list (fn [_] (throw (ex-info secret {})))}) (rpc "tools/list"))
            e (:error (body-of r))]
        (is (= 200 (:status r)))
        (is (= -32603 (:code e)))
        (is (clean? r))
        (is (= [[secret "tools/list"]] (map rest @seen)))
        (is (str/includes? (:message e) (ffirst @seen)) "the reference logged is the one sent")))
    (testing "a result JSON cannot carry is the tool's fault"
      (doseq [bad [{:d (java.time.LocalDate/of 2026 1 1)} {:x Double/NaN}]]
        (reset! seen [])
        (let [r (post (d {:call-tool (fn [& _] bad)}) (rpc "tools/call" {:name "echo"}))
              res (:result (body-of r))]
          (is (= 200 (:status r)))
          (is (true? (:isError res)))
          (is (str/includes? (get-in res [:content 0 :text]) (ffirst @seen)))
          (is (= "echo" (last (first @seen)))))))
    (testing "an Error, not only an Exception"
      (let [r (post (d {:call-tool (fn [& _] (assert false secret))}) (rpc "tools/call" {:name "echo"}))]
        (is (true? (get-in (body-of r) [:result :isError])))
        (is (clean? r))))
    (testing "the authenticator or the rate limiter throws: 500 with a reference"
      (doseq [over [{:authenticate (fn [_] (throw (ex-info secret {})))}
                    {:rate-limit? (fn [_] (throw (ex-info secret {})))}]]
        (reset! seen [])
        (let [r (post (d over) (rpc "ping"))]
          (is (= 500 (:status r)))
          (is (= -32603 (get-in (body-of r) [:error :code])))
          (is (clean? r))
          (is (= "http" (last (first @seen)))))))
    (testing "a request with no body is a parse error, not an exception"
      (let [r ((mcp/routes (deps)) {:request-method :post :uri "/mcp" :headers {"authorization" (str "Bearer " good)}})]
        (is (= 400 (:status r)))
        (is (= -32700 (get-in (body-of r) [:error :code])))))))

;; ---------------------------------------------------------------------------
;; The small rules
;; ---------------------------------------------------------------------------

(defn- raw-post [d headers body & [uri]]
  ((mcp/routes d) {:request-method :post :uri (or uri "/mcp")
                   :headers (merge {"host" "127.0.0.1:9632" "authorization" (str "Bearer " good)} headers)
                   :body (java.io.ByteArrayInputStream. (.getBytes (json/write body) "UTF-8"))}))

(deftest the-bearer-scheme-is-case-insensitive
  (doseq [auth ["bearer good" "BEARER good" "Bearer   good"]]
    (is (= 200 (:status (raw-post (deps) {"authorization" auth} (rpc "ping")))) auth))
  (is (= 401 (:status (raw-post (deps) {"authorization" "Basic good"} (rpc "ping")))))
  (is (= 401 (:status (raw-post (deps) {"authorization" "Bearer"} (rpc "ping"))))))

(deftest a-401-names-the-scheme
  (is (= "Bearer" (get-in (post (deps) (rpc "ping") {:auth nil}) [:headers "WWW-Authenticate"]))))

(deftest retry-after-is-always-a-number
  (doseq [answer [{:allowed? false} {:allowed? false :retry-after-secs nil} {:allowed? false :retry-after-secs 0}
                  {:allowed? false :retry-after-secs 2.2}]]
    (let [r (post (deps {:rate-limit? (constantly answer)}) (rpc "ping"))]
      (is (= 429 (:status r)))
      (is (pos? (parse-long (get-in r [:headers "Retry-After"]))) (pr-str answer)))))

(deftest the-limiter-forgets-idle-callers
  (let [limit (mcp/rate-limiter 5 30)
        state (some #(when (instance? clojure.lang.Atom %) %)
                    (map #(.get ^java.lang.reflect.Field % limit)
                         (doto (.getDeclaredFields (class limit)) (->> (run! #(.setAccessible ^java.lang.reflect.Field % true))))))]
    (dotimes [i 1000] (limit (str "caller-" i)))
    (Thread/sleep 60)
    (dotimes [_ 100] (limit "the-one-still-here"))
    (is (some? state))
    (is (<= (count (:seen @state)) 3) "the thousand idle callers are gone")
    (is (false? (:allowed? (limit "the-one-still-here"))) "and the limit still holds")))

(deftest a-batch-has-a-cap
  (let [batch (fn [n] (vec (repeat n (rpc "ping"))))]
    (is (= 200 (:status (post (deps) (batch 50)))))
    (let [r (post (deps) (batch 51))]
      (is (= 400 (:status r)))
      (is (= -32600 (get-in (body-of r) [:error :code]))))
    (is (= 200 (:status (post (deps {:max-batch 60}) (batch 51)))))
    (is (= 400 (:status (post (deps {:max-batch 2}) (batch 3)))))))

(deftest tools-call-needs-a-name
  (doseq [params [nil {} {:name ""} {:name 7}]]
    (is (= -32602 (get-in (body-of (post (deps) (rpc "tools/call" params))) [:error :code])) (pr-str params))))

(deftest allowed-origins
  (let [from (fn [d origin] (:status (raw-post d {"origin" origin} (rpc "ping"))))]
    (is (= 403 (from (deps) "https://app.example.com")))
    (is (= 200 (from (deps {:allowed-origins #{"https://app.example.com"}}) "https://app.example.com")))
    (is (= 403 (from (deps {:allowed-origins #{"https://app.example.com"}}) "https://evil.example.com")))
    (is (= 200 (from (deps) "http://127.0.0.1:9632")) "the request's own host, as before")))

(deftest the-endpoint-can-be-mounted-elsewhere
  (let [d (deps {:path "/api/v2/mcp" :public-path "/gateway/api/v2/mcp"})]
    (is (= 200 (:status (raw-post d {} (rpc "ping") "/api/v2/mcp"))))
    (is (nil? (raw-post d {} (rpc "ping") "/mcp")))
    (is (= 405 (:status ((mcp/routes d) {:request-method :get :uri "/api/v2/mcp"}))))
    (testing "the SSE handshake announces the path clients see"
      (let [stream ((mcp/routes d) {:request-method :get :uri "/api/v2/mcp/sse"
                                    :headers {"host" "127.0.0.1:9632" "authorization" (str "Bearer " good)}})
            out (java.io.ByteArrayOutputStream.)]
        (doto (Thread. #((:body stream) out)) (.setDaemon true) (.start))
        (Thread/sleep 120)
        (is (str/includes? (str out) "data: /gateway/api/v2/mcp/messages?sessionId="))))))

(deftest an-endpoint-can-have-sessions-of-its-own
  (let [open (fn [d] (let [stream ((mcp/routes d) {:request-method :get :uri "/mcp/sse"
                                                    :headers {"host" "127.0.0.1:9632" "authorization" (str "Bearer " good)}})
                           out (java.io.ByteArrayOutputStream.)]
                       (when (= 200 (:status stream))
                         (doto (Thread. #((:body stream) out)) (.setDaemon true) (.start))
                         (Thread/sleep 100)
                         (second (re-find #"sessionId=([0-9a-f-]{36})" (str out))))))
        ping (fn [d sid] (:status ((mcp/routes d) {:request-method :post :uri "/mcp/messages" :query-string (str "sessionId=" sid)
                                                   :headers {"host" "127.0.0.1:9632" "authorization" (str "Bearer " good)}
                                                   :body (java.io.ByteArrayInputStream. (.getBytes (json/write (rpc "ping")) "UTF-8"))})))
        a (deps {:sessions (mcp/new-sessions) :sse-max-sessions-per-caller 1})
        b (deps {:sessions (mcp/new-sessions)})
        sid (open a)]
    (is (some? sid))
    (is (= 202 (ping a sid)))
    (is (= 404 (ping b sid)) "another endpoint's table does not know it")
    (is (nil? (open a)) "the endpoint's own cap of one stream per caller")
    (is (some? (open b)) "which the other endpoint does not share")))
