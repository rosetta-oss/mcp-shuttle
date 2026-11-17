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
  "The spec's rule: echo a version we speak, otherwise answer with our
   preferred one — never fail the handshake over it."
  (testing "a version we speak comes back unchanged"
    (is (= "2024-11-05"
           (get-in (body-of (post (deps) (rpc "initialize" {:protocolVersion "2024-11-05"})))
                   [:result :protocolVersion]))))
  (testing "one we do not speak gets our preferred version, not an error"
    (let [r (body-of (post (deps) (rpc "initialize" {:protocolVersion "1999-01-01"})))]
      (is (= "2025-03-26" (get-in r [:result :protocolVersion])))
      (is (nil? (:error r)))))
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
