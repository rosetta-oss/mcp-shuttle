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
