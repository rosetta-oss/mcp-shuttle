;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.http
  "MCP over HTTP as a Ring handler: the wire, and nothing else.
   Callers may only require this ns; `shuttle.impl.*` is private.

   Everything an MCP server shares with every other MCP server is protocol:
   JSON-RPC framing, version negotiation, batching, the two wire shapes, the
   SSE session bookkeeping. Everything that differs is judgement: who a caller
   is, what they may call, and what a call does. The judgement arrives as
   injected functions; this library never learns its host's vocabulary, and
   its tests assert that by scanning the source.

   ## Deps

   - `:authenticate` — `(fn [request])` → `nil` (refuse) or `{:id … :ctx …}`.
     `:id` is opaque, used only for rate-limit keying and SSE session
     ownership; `:ctx` is handed back to the two tool fns untouched. **Never
     return a credential in `:id`** — it outlives the request in the session
     map. Parse the bearer with `bearer` so there is one such parse;
   - `:tools-list` — `(fn [ctx])` → the vector `tools/list` announces. It takes
     ctx so capability filtering is ONE decision shared with dispatch, not two
     that can disagree. Entries are in the newest protocol's shape
     (`annotations`, `title`, `outputSchema`); the wire drops what the
     caller's negotiated revision does not define;
   - `:call-tool` — `(fn [ctx tool-name arguments])` → result. `{:error …}` is
     a refusal (becomes an `isError` tool result the agent can read and act
     on); a thrown exception is a server fault and is reported as one;
   - `:server-info` — `{:name … :version … [:title …]}` for `initialize`, or
     `(fn [ctx])` returning it, so a host can describe what this caller's
     credential names (`title` is sent only to 2025-06-18 clients);
   - `:instructions` — optional string for `initialize`, or `(fn [ctx])`;
   - `:unauthorized-message` — what a 401 says, injected because the honest
     sentence differs per host;
   - `:sse-writer` — wraps `(fn [^OutputStream out])` into whatever the host's
     HTTP adapter accepts as a streaming body;
   - `:resources-list` + `:resource-read`, `:prompts-list` + `:prompt-get` —
     optional; a host that injects them gets the `resources` / `prompts`
     capability and methods (see `impl.transport` for the shapes);
   - `:rate-limit?` — optional `(fn [id] {:allowed? … :retry-after-secs …})`.
     Absent means no limiting: a visible config fact, not a silent default;
   - `:max-body-bytes` — optional cap on one POSTed message, refused with 413
     and JSON-RPC -32600 naming the cap. Absent means no cap."
  (:require [shuttle.impl.transport :as transport]))

(defn routes
  "Ring handler for the four MCP routes; nil for any other request, so it
   composes with a host's own routes. **Does not start a server**: feed it a
   request map to test.

   Every host mounts THIS, so the route set cannot drift between hosts."
  [deps]
  (fn [{:keys [request-method uri] :as request}]
    (case [request-method uri]
      [:post "/mcp"] (transport/handle-post deps request)
      [:get "/mcp"] (transport/handle-get request)
      ;; The 2024-11-05 transport, kept because many clients and gateways still
      ;; speak only it. Same dispatch, same auth; only the framing differs.
      [:get "/mcp/sse"] (transport/handle-sse deps request)
      [:post "/mcp/messages"] (transport/handle-messages deps request)
      nil)))

(def data-methods
  "What the data profile answers: the handshake and tools, nothing else."
  #{"initialize" "notifications/initialized" "ping" "tools/list" "tools/call"})

(defn data-routes
  "Ring handler for the **data profile**: MCP for programs (other services,
   batch jobs) rather than for people and their agents. One route, `POST` at
   `:path` (default \"/mcp/data\"); `GET` there answers 405. Compared with
   `routes`:
   - no HTTP+SSE transport and never a session: every request stands alone;
   - tools only: resources, prompts and instructions are not announced, and
     their methods are -32601;
   - limits are required: `:rate-limit?` and `:max-body-bytes` must be given
     (`rate-limiter` makes the first);
   - every failure a program can meet carries a stable code in its JSON text:
     `invalid_arguments`, `unknown_tool`, `timeout`, `internal_error` (with a
     `reference`), or the tool's own `:code` on a refusal.
   Build the tools with `shuttle.tools/data-deps`, which insists on an
   `:output` schema and a `:timeout-ms` for every tool.

   Same deps as `routes` otherwise. Returns nil for any other request."
  [deps]
  (doseq [k [:rate-limit? :max-body-bytes]]
    (when (nil? (get deps k))
      (throw (ex-info (str "data-routes needs " k) {:missing k}))))
  (let [path (or (:path deps) "/mcp/data")
        deps (-> deps
                 (dissoc :resources-list :resource-read :prompts-list :prompt-get :instructions)
                 (assoc :profile :data :methods data-methods))]
    (fn [{:keys [request-method uri] :as request}]
      (when (= uri path)
        (case request-method
          :post (transport/handle-post deps request)
          (transport/handle-get request))))))

(defn rate-limiter
  "A `:rate-limit?` for one process: at most `n` requests per caller id in any
   `per-ms` window (a sliding window kept per id). Several instances behind a
   balancer each count on their own; a shared limit needs the host's own
   function."
  [n per-ms]
  (let [seen (atom {})]
    (fn [id]
      (let [now (System/currentTimeMillis)
            since (- now per-ms)
            [old] (swap-vals! seen update id
                              (fn [ts]
                                (let [ts (filterv #(> % since) ts)]
                                  (if (< (count ts) n) (conj ts now) ts))))
            before (filterv #(> % since) (get old id))]
        (if (< (count before) n)
          {:allowed? true}
          {:allowed? false
           :retry-after-secs (max 1 (long (Math/ceil (/ (- (+ (first before) per-ms) now) 1000.0))))})))))

(defn bearer
  "The request's Bearer credential, or nil.

   Exposed for hosts to use inside their own `:authenticate`: the prefix
   comparison should exist once, not once per endpoint."
  [request]
  (transport/bearer request))

(defn handle-post
  "POST /mcp on its own, for a host that assembles its own route table."
  [deps request]
  (transport/handle-post deps request))

(defn handle-get
  "GET /mcp on its own — 405 naming POST (this server opens no server-initiated
   stream)."
  [request]
  (transport/handle-get request))

(defn handle-sse
  "GET /mcp/sse on its own — the 2024-11-05 transport's handshake and stream."
  [deps request]
  (transport/handle-sse deps request))

(defn handle-messages
  "POST /mcp/messages on its own — the 2024-11-05 transport's upstream leg."
  [deps request]
  (transport/handle-messages deps request))
