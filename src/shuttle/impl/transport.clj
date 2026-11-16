;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.impl.transport
  "MCP over BOTH remote transports.

   - Streamable HTTP (2025-03-26, the current standard): single POST /mcp,
     JSON-RPC in / application/json out. GET /mcp answers 405 per spec (this
     server opens no server-initiated stream).
   - HTTP+SSE (2024-11-05, deprecated but still what many clients and gateways
     speak): GET /mcp/sse opens the event stream and announces the session's
     message endpoint; the client POSTs JSON-RPC to /mcp/messages?sessionId=…
     and each response is pushed back over the stream. Compatibility layer —
     same dispatch, same auth; only the framing differs.

   `initialize` negotiates the protocol version: a client-requested version we
   support is echoed back; anything else gets our preferred version (per the
   2025-03-26 spec's version-negotiation rule).

   ## What this namespace must NOT know

   It carries the protocol and nothing else: framing, negotiation, batching,
   the two wire shapes, the session bookkeeping the SSE leg needs. What a
   credential means, what may be called and what a call does are all injected,
   and the module's tests assert that absence by scanning this source for the
   vocabulary of either host.

   That absence is the point: every host mounts the same code with its own
   authentication and its own tools, so no host's copy can drift at the next
   spec revision.

   ## Injected deps

   - `:authenticate` — `(fn [request])` → `nil` (refuse) or
     `{:id \"…\" :ctx …}`. `:id` is an opaque identity string: this namespace
     uses it for rate-limit keying and SSE session ownership, and never
     interprets it. `:ctx` is handed back to `:tools-list` / `:call-tool`
     untouched. **A credential must never come back inside `:id`** — that id
     outlives the request in the SSE session map;
   - `:tools-list` — `(fn [ctx])` → the vector `tools/list` announces. Taking
     ctx is what lets a host compute capability filtering per caller, so that
     `tools/list` and dispatch are one decision rather than two;
   - `:call-tool` — `(fn [ctx tool-name arguments])` → the tool's result.
     `{:error …}` is a REFUSAL and becomes an `isError` tool result; a thrown
     exception is a server fault and is reported as one. Keeping those apart
     is load-bearing: an agent must read and act on the first, and cannot do
     anything about the second;
   - `:server-info` — `{:name … :version … [:title …]}` announced by
     `initialize`, or `(fn [ctx])` returning it;
   - `:instructions` — optional string handed to the client on `initialize`,
     or `(fn [ctx])` returning it;
   - `:unauthorized-message` — what a 401 says. Injected because the honest
     sentence differs per host, and a generic one would teach the caller to
     look for the wrong kind of key;
   - `:sse-writer` — wraps a `(fn [^OutputStream out])` into whatever the
     host's HTTP adapter accepts as a streaming body. Injected because the
     streaming protocol belongs to the adapter, not to MCP;
   - `:resources-list` / `:resource-read` — optional `(fn [ctx])` → the
     resource descriptors, and `(fn [ctx uri])` → `{:contents […]}` or nil;
     with them `initialize` announces the `resources` capability;
   - `:prompts-list` / `:prompt-get` — optional `(fn [ctx])` → the prompt
     descriptors, and `(fn [ctx name arguments])` → `{:description …
     :messages […]}` or `{:error message}`; with them, `prompts`;
   - `:on-fault` — optional `(fn [reference ^Throwable e tool-name])`, called
     when a tool THROWS. The client only ever sees a generic sentence plus the
     opaque `reference`; the exception text (which may carry JDBC / SQL / path
     details) goes here and nowhere else. Absent → the JDK's
     `System.Logger` at ERROR;
   - `:rate-limit?` — optional `(fn [id] {:allowed? bool :retry-after-secs n})`.
     Absent means no limiting, which is a visible config fact rather than a
     silent default."
  (:require [clojure.string :as str]
            [shuttle.impl.jsonrpc :as rpc])
  (:import [java.io IOException]
           [java.util UUID]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:private header-authorization "authorization")
(def ^:private bearer-prefix "Bearer ")

;; ---------------------------------------------------------------------------
;; JSON-RPC method dispatch
;; ---------------------------------------------------------------------------

(def ^:private supported-protocol-versions
  "Protocol revisions this server can speak. Preferred first — a client asking
   for a version we don't know gets the preferred one back (spec: 'the server
   MUST respond with another protocol version it supports')."
  ["2025-03-26" "2025-06-18" "2024-11-05"])

(defn- negotiate-protocol-version [requested]
  (if (some #(= % requested) supported-protocol-versions)
    requested
    (first supported-protocol-versions)))

;; ---------------------------------------------------------------------------
;; Which protocol revision a message is read under
;;
;; Tool annotations arrived in 2025-03-26; a tool's `title`, `outputSchema`,
;; a result's `structuredContent` and `serverInfo.title` in 2025-06-18. A host
;; describes its tools in the NEWEST shape and this namespace removes what the
;; caller's revision does not define — one judgement, made where versions are
;; negotiated, instead of one per host.
;;
;; Streamable HTTP keeps no session here, so the revision of a POST is its
;; `MCP-Protocol-Version` header (2025-06-18 makes clients send it after
;; `initialize`). Absent or unknown, the spec says to assume 2025-03-26 — which
;; is also exactly what every client that predates the header expects. The SSE
;; leg has a session, and remembers what its `initialize` negotiated.
;; ---------------------------------------------------------------------------

(def ^:private assumed-protocol-version
  "Spec 2025-06-18, Basic › Transports: without the header, assume 2025-03-26."
  "2025-03-26")

(defn- at-least? [version floor] (not (neg? (compare (str version) floor))))

(defn- header-protocol-version [request]
  (let [h (get-in request [:headers "mcp-protocol-version"])]
    (when (some #(= % h) supported-protocol-versions) h)))

(defn- shape-tool
  "One `tools/list` entry, without the fields `version` does not define."
  [version tool]
  (cond
    (at-least? version "2025-06-18") tool
    (at-least? version "2025-03-26") (dissoc tool "title" :title "outputSchema" :outputSchema)
    :else (dissoc tool "title" :title "outputSchema" :outputSchema "annotations" :annotations)))

(defn- structured-content
  "The result again as `structuredContent`, when the host marked it (toolkit
   `structured-content-key`, by name) and the revision defines the field. The
   text block stays: a client that ignores structured content reads the same
   JSON there (2025-06-18: a tool returning structured content SHOULD also
   return it serialized as text)."
  [version result]
  (when (and (at-least? version "2025-06-18") (map? result)
             (get (meta result) :mcp/structured-content))
    result))

(defn- resolve-per-ctx
  "A dep that may be a value or `(fn [ctx])` (a host may let
   the handshake describe whatever the caller's credential names)."
  [x ctx]
  (if (fn? x) (x ctx) x))

(defn- handle-initialize [deps id params ctx]
  (let [version (negotiate-protocol-version (:protocolVersion params))
        info (resolve-per-ctx (:server-info deps) ctx)
        instructions (resolve-per-ctx (:instructions deps) ctx)]
    (rpc/success id
                 (cond-> {:protocolVersion version
                          :capabilities (cond-> {:tools {:listChanged false}}
                                          ;; Announced only by a host that
                                          ;; serves them.
                                          (:resources-list deps)
                                          (assoc :resources {:subscribe false :listChanged false})
                                          (:prompts-list deps)
                                          (assoc :prompts {:listChanged false}))
                          :serverInfo (if (at-least? version "2025-06-18")
                                        info
                                        (dissoc info :title "title"))}
                   ;; Absent instructions stay absent rather than becoming an
                   ;; empty string: a client can tell "this server has nothing to
                   ;; say" from "this server says nothing", and the two differ.
                   instructions (assoc :instructions instructions)))))

(defn- log-fault! [reference ^Throwable e tool-name]
  (.log ^java.lang.System$Logger (System/getLogger "shuttle")
        java.lang.System$Logger$Level/ERROR
        ^String (str "MCP tool " tool-name " failed; reference " reference)
        e))

(defn fault-message
  "What the CLIENT is told about a thrown exception: nothing from the
   exception itself, only a reference to quote."
  [reference]
  (str "the tool failed with an internal server error (reference " reference
       "); the server log has the details under that reference"))

(defn- handle-tools-call [deps id params ctx version]
  (let [{:keys [name arguments]} params
        result (try
                 ((:call-tool deps) ctx name (or arguments {}))
                 (catch Exception e
                   ;; A thrown exception is a SERVER fault, not an answer.
                   ;; Refusals travel as {:error …} data below. Its message is
                   ;; NOT forwarded: a JDBC/SQL text can name hosts, schemas,
                   ;; paths or values. Log in full, return a
                   ;; reference.
                   (let [reference (subs (str (UUID/randomUUID)) 0 8)]
                     (try ((or (:on-fault deps) log-fault!) reference e name)
                          (catch Throwable _ nil))
                     {:error (fault-message reference) :fault true})))]
    (if (:error result)
      (rpc/tool-error id (if (:fault result)
                           (:error result)
                           ;; A refusal is an answer: hand the agent the whole
                           ;; structured finding, because "which key was wrong"
                           ;; is exactly what it needs to fix the next call.
                           (pr-str (dissoc result :fault))))
      (rpc/tool-content id result (structured-content version result)))))

(defn- optional-method
  "A method the host may not serve: -32601, exactly as an unknown method,
   when the dep is absent."
  [deps k id method f]
  (if-let [g (get deps k)]
    (f g)
    (rpc/error id -32601 (str "Method not found: " method))))

(defn- dispatch-method [deps {:keys [method params id]} ctx version]
  (case method
    "initialize"                (handle-initialize deps id params ctx)
    "notifications/initialized" nil
    "ping"                      (rpc/success id {})
    "tools/list"                (rpc/success id {:tools (mapv #(shape-tool version %)
                                                              ((:tools-list deps) ctx))})
    "tools/call"                (handle-tools-call deps id params ctx version)
    ;; Resources and prompts: optional, read-only, and
    ;; computed from the same ctx as the tools, so a caller is offered exactly
    ;; what its credential reaches. Spec: an unknown resource is -32002, a
    ;; bad prompt name or argument -32602.
    "resources/list"            (optional-method deps :resources-list id method
                                                 #(rpc/success id {:resources (vec (% ctx))}))
    "resources/templates/list"  (optional-method deps :resources-list id method
                                                 (fn [_] (rpc/success id {:resourceTemplates []})))
    "resources/read"            (optional-method deps :resource-read id method
                                                 #(if-let [r (% ctx (:uri params))]
                                                    (rpc/success id r)
                                                    (rpc/error id -32002 "Resource not found")))
    "prompts/list"              (optional-method deps :prompts-list id method
                                                 #(rpc/success id {:prompts (vec (% ctx))}))
    "prompts/get"               (optional-method deps :prompt-get id method
                                                 #(let [r (% ctx (:name params) (or (:arguments params) {}))]
                                                    (if (:error r)
                                                      (rpc/error id -32602 (:error r))
                                                      (rpc/success id r))))
    (rpc/error id -32601 (str "Method not found: " method))))

(defn- dispatch-element
  "Dispatch one decoded JSON-RPC message (a batch element, or the whole body of
   a non-batch request). Returns the response body string, or nil when there is
   no reply — a notification. A non-Request object gets an -32600 Invalid
   Request echoing its :id when present."
  [deps decoded ctx version]
  (if-let [parsed (rpc/as-request decoded)]
    (dispatch-method deps parsed ctx version)
    (rpc/error (when (map? decoded) (:id decoded)) -32600 "Invalid Request")))

;; ---------------------------------------------------------------------------
;; Shared request gates
;; ---------------------------------------------------------------------------

(defn- origin-mismatch?
  "True when the request carries an Origin header whose authority differs from
   the request's own Host — the MCP spec's DNS-rebinding guard ('servers MUST
   validate the Origin header'). Non-browser MCP clients send no Origin and
   pass untouched; a browser page on another origin is refused."
  [request]
  (boolean
   (when-let [origin (get-in request [:headers "origin"])]
     (let [authority (-> origin
                         (str/replace #"^[a-zA-Z][a-zA-Z0-9+.-]*://" "")
                         (str/split #"/")
                         first)
           host      (get-in request [:headers "host"])]
       (and (seq authority) (some? host) (not= authority host))))))

(def ^:private origin-forbidden
  {:status 403
   :headers {"Content-Type" "application/json"}
   :body (rpc/error nil -32003 "Origin not allowed")})

(defn bearer
  "The Bearer credential, or nil. Public because every host's `:authenticate`
   needs exactly this parse, and a second copy of it is a second place for the
   prefix comparison to go wrong."
  [request]
  (let [auth (get-in request [:headers header-authorization])]
    (when (and (string? auth) (str/starts-with? auth bearer-prefix))
      (subs auth (count bearer-prefix)))))

(defn- authenticate
  "Run the injected authenticator. → `{:id … :ctx …}`, or nil.

   The credential is consumed HERE, before any JSON-RPC parsing, and what
   comes back is an id plus an opaque ctx. Nothing downstream of this line can
   see the secret, which is why no tool argument, finding or trace can carry
   it."
  [deps request]
  ((:authenticate deps) request))

(defn- unauthorized [deps]
  {:status 401
   :headers {"Content-Type" "application/json"}
   :body (rpc/error nil -32001 (:unauthorized-message deps))})

(defn- allowed?
  "The optional rate limiter. Absent = no limiting; see the ns docstring on why
   that is a config fact rather than a silent default."
  [deps id]
  (if-let [f (:rate-limit? deps)] (f id) {:allowed? true}))

(defn- rate-limited [retry-after]
  {:status 429
   :headers {"Content-Type" "application/json" "Retry-After" (str retry-after)}
   :body (rpc/error nil -32029 (str "Rate limit exceeded — retry after " retry-after "s"))})

;; The optional body cap (`:max-body-bytes`). Absent = no cap, as before; a host
;; sets it when its other doors already have one. Over the cap is refused, never
;; truncated: a truncated JSON-RPC message parses as another message or not at all.
(defn- read-body [deps request]
  (let [^java.io.InputStream in (:body request)
        cap (:max-body-bytes deps)]
    (cond
      (nil? cap) {:text (slurp in)}
      (nil? in) {:text ""}
      :else
      (let [buf (byte-array 8192)
            out (java.io.ByteArrayOutputStream.)]
        (loop []
          (let [n (.read in buf)]
            (cond
              (neg? n) {:text (.toString out "UTF-8")}
              (> (+ (.size out) n) cap) {:over cap}
              :else (do (.write out buf 0 n) (recur)))))))))

(defn- too-large [cap]
  {:status 413
   :headers {"Content-Type" "application/json"}
   :body (rpc/error nil -32600 (str "Request body exceeds the cap of " cap " bytes"))})

;; ---------------------------------------------------------------------------
;; Streamable HTTP (2025-03-26)
;; ---------------------------------------------------------------------------

(defn handle-get
  "GET /mcp — this server opens no server-initiated stream, so per the
   Streamable HTTP spec it answers 405 (a client then knows to POST here;
   older SSE-transport clients should use GET /mcp/sse instead)."
  [_request]
  {:status 405 :headers {"Allow" "POST"} :body ""})

(defn- handle-post* [deps request]
  (if-let [{:keys [id ctx]} (authenticate deps request)]
    (let [rl (allowed? deps id)
          version (or (header-protocol-version request) assumed-protocol-version)]
      (if-not (:allowed? rl)
        (rate-limited (:retry-after-secs rl))
        (let [{:keys [text over]} (read-body deps request)
              decoded (when-not over (rpc/parse-json text))]
          (cond
            over (too-large over)

            ;; Genuine JSON parse failure → -32700.
            (rpc/parse-error? decoded)
            {:status 400 :headers {"Content-Type" "application/json"}
             :body (rpc/error nil -32700 "Parse error")}

            ;; A top-level JSON array is a JSON-RPC 2.0 batch: auth was checked
            ;; once above; dispatch EACH element on the same path and return an
            ;; array of the responses (notifications contribute none). An empty
            ;; array is itself an Invalid Request per spec.
            (sequential? decoded)
            (if (empty? decoded)
              {:status 400 :headers {"Content-Type" "application/json"}
               :body (rpc/error nil -32600 "Invalid Request")}
              (let [responses (keep #(dispatch-element deps % ctx version) decoded)]
                (if (seq responses)
                  {:status 200 :headers {"Content-Type" "application/json"}
                   :body (str "[" (str/join "," responses) "]")}
                  {:status 202})))

            :else
            (if-let [response (dispatch-element deps decoded ctx version)]
              {:status 200 :headers {"Content-Type" "application/json"} :body response}
              ;; Notification — no response needed
              {:status 202})))))
    (unauthorized deps)))

(defn handle-post
  "POST /mcp — the single endpoint for all JSON-RPC messages."
  [deps request]
  (if (origin-mismatch? request) origin-forbidden (handle-post* deps request)))

;; ---------------------------------------------------------------------------
;; Legacy HTTP+SSE transport (2024-11-05) — compatibility layer
;;
;; Deprecated by the spec but still the only transport many clients and
;; gateways speak. Handshake: GET /mcp/sse opens the stream; the FIRST event
;; announces the session's message endpoint; the client then POSTs each
;; JSON-RPC message to /mcp/messages?sessionId=… and reads every response off
;; the stream. Same dispatch and auth as POST /mcp — only the framing differs.
;; ---------------------------------------------------------------------------

(defonce ^:private sse-sessions
  ;; sid -> {:queue LinkedBlockingQueue :id str} — one entry per open stream,
  ;; removed when it closes. Only the caller's ID is held: /mcp/messages
  ;; carries its own Authorization header, so no credential ever needs to
  ;; outlive a request.
  (atom {}))

(def ^:private sse-max-sessions-per-id
  "Concurrency cap per caller. Each open stream parks a worker thread forever
   in the poll loop, so an uncapped caller can exhaust the pool and starve
   every endpoint."
  8)

(def ^:private sse-max-sessions-global
  "Global cap — a backstop so many callers can't collectively drain the worker
   pool. Kept well under a typical Jetty thread pool."
  256)

(def ^:private sse-cap-retry-after-secs 5)

(def ^:private sse-keepalive-ms
  "Comment-line keepalive cadence for an otherwise idle stream: idle proxies
   drop silent connections, and the periodic write is also how a dead client is
   DETECTED — the write throws, the loop exits, the session is cleaned up."
  15000)

(defn- register-sse-session!
  "Atomically register a session, enforcing both caps inside the swap (no
   check-then-act race). Returns the sid, or nil when a cap is reached."
  [id]
  (let [sid (str (UUID/randomUUID))
        sessions (swap! sse-sessions
                        (fn [sessions]
                          (cond
                            (>= (count sessions) sse-max-sessions-global) sessions
                            (>= (count (filter #(= id (:id %)) (vals sessions)))
                                sse-max-sessions-per-id) sessions
                            :else (assoc sessions sid {:queue (LinkedBlockingQueue.)
                                                       :id id}))))]
    (when (contains? sessions sid) sid)))

(defn- close-sse-session! [sid] (swap! sse-sessions dissoc sid))

(defn- push-sse-response!
  "Queue a response onto the session's stream. true when the session exists AND
   this caller owns it; nil otherwise — the caller 404s, because an ownership
   failure is deliberately indistinguishable from an unknown session."
  [sid id response]
  (when-let [{:keys [queue] :as s} (get @sse-sessions sid)]
    (when (= id (:id s))
      (.offer ^LinkedBlockingQueue queue response)
      true)))

(defn- query-param [request k]
  (some->> (:query-string request)
           (re-find (re-pattern (str "(?:^|&)" k "=([^&]+)")))
           second))

(defn handle-sse
  "GET /mcp/sse — the old MCP transport's handshake + downstream channel."
  [deps request]
  (if (origin-mismatch? request)
    origin-forbidden
    (if-let [{:keys [id]} (authenticate deps request)]
      (let [rl (allowed? deps id)]
        (if-not (:allowed? rl)
          (rate-limited (:retry-after-secs rl))
          (if-let [sid (register-sse-session! id)]
            (let [queue (:queue (get @sse-sessions sid))
                  write-body (:sse-writer deps)]
              {:status 200
               :headers {"Content-Type" "text/event-stream"
                         "Cache-Control" "no-cache"
                         "X-Accel-Buffering" "no"}
               :body (write-body
                      (fn [^java.io.OutputStream out]
                        (let [w (java.io.OutputStreamWriter. out "UTF-8")]
                          (try
                            ;; the handshake: where THIS session's messages go
                            (.write w (str "event: endpoint\ndata: /mcp/messages?sessionId="
                                           sid "\n\n"))
                            (.flush w)
                            (loop []
                              (if-let [resp (.poll ^LinkedBlockingQueue queue
                                                   sse-keepalive-ms TimeUnit/MILLISECONDS)]
                                (do (.write w (str "event: message\ndata: " resp "\n\n"))
                                    (.flush w) (recur))
                                (do (.write w ": keepalive\n\n") (.flush w) (recur))))
                            (catch IOException _ nil) ; client went away — normal
                            (catch Exception _ nil)
                            (finally (close-sse-session! sid))))))})
            ;; Cap reached — refuse the open with the same 429 shape, so the
            ;; parked-thread exhaustion can't be opened past the cap.
            (rate-limited sse-cap-retry-after-secs))))
      (unauthorized deps))))

(defn handle-messages
  "POST /mcp/messages?sessionId=… — the old MCP transport's upstream leg: parse
   the message, dispatch it, push the response onto the session's stream,
   answer 202. Unknown or foreign session → 404."
  [deps request]
  (if (origin-mismatch? request)
    origin-forbidden
    (if-let [{:keys [id ctx]} (authenticate deps request)]
      (let [rl (allowed? deps id)]
        (if-not (:allowed? rl)
          (rate-limited (:retry-after-secs rl))
          (let [sid (query-param request "sessionId")]
            (if-not (and sid (get @sse-sessions sid))
              {:status 404 :headers {"Content-Type" "application/json"}
               :body (rpc/error nil -32002 "Unknown session — open GET /mcp/sse first")}
              (let [{:keys [text over]} (read-body deps request)
                    decoded (when-not over (rpc/parse-json text))]
                (cond
                  over (too-large over)

                  (rpc/parse-error? decoded)
                  {:status 400 :headers {"Content-Type" "application/json"}
                   :body (rpc/error nil -32700 "Parse error")}

                  :else
                  (let [session-version (get-in @sse-sessions [sid :protocol])
                        version (or (header-protocol-version request) session-version
                                    assumed-protocol-version)
                        response (dispatch-element deps decoded ctx version)]
                    ;; The SSE leg HAS a session: remember what its
                    ;; `initialize` settled, so later messages on it are read
                    ;; under that revision even without the header.
                    (when (and (map? decoded) (= "initialize" (:method decoded)))
                      (swap! sse-sessions
                             (fn [m] (if (contains? m sid)
                                       (assoc-in m [sid :protocol]
                                                 (negotiate-protocol-version
                                                  (get-in decoded [:params :protocolVersion])))
                                       m))))
                    (if (or (nil? response) (push-sse-response! sid id response))
                      {:status 202 :body ""}
                      {:status 404 :headers {"Content-Type" "application/json"}
                       :body (rpc/error nil -32002
                                        "Unknown session — open GET /mcp/sse first")}))))))))
      (unauthorized deps))))
