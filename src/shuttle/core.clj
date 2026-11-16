;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.core
  "MCP's protocol, without a transport: one decoded JSON-RPC message, the
   caller's context and the negotiated revision in; the JSON reply out. The
   HTTP transport (`shuttle.http`) authenticates, limits and frames; this
   namespace decides what a message means. Tests and other transports call
   `handle` directly.

   The host's judgement arrives in the same deps map the transport takes:
   `:tools-list`, `:call-tool`, `:server-info`, `:instructions`,
   `:resources-list` / `:resource-read`, `:prompts-list` / `:prompt-get`,
   `:on-fault` (see `shuttle.http`); `:methods`, when given, is the set of
   methods answered (others are -32601), and `:profile :data` makes a fault's
   text JSON with a stable code (see `shuttle.http/data-routes`). `shuttle.tools` builds `:tools-list` and
   `:call-tool` from tool definitions with malli schemas."
  (:require [shuttle.impl.jsonrpc :as rpc])
  (:import [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Protocol revisions
;; ---------------------------------------------------------------------------

(def supported-protocol-versions
  "Protocol revisions this server can speak. Preferred first — a client asking
   for a version we don't know gets the preferred one back (spec: 'the server
   MUST respond with another protocol version it supports')."
  ["2025-03-26" "2025-06-18" "2024-11-05"])

(defn negotiate-protocol-version
  "The revision `initialize` answers with: the requested one when supported, else the preferred one."
  [requested]
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

(def assumed-protocol-version
  "Spec 2025-06-18, Basic › Transports: without the header, assume 2025-03-26."
  "2025-03-26")

(defn- at-least? [version floor] (not (neg? (compare (str version) floor))))

(defn supported-version
  "v when this library speaks that revision, else nil."
  [v]
  (when (some #(= % v) supported-protocol-versions) v))

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
                     {:error (fault-message reference) :fault true :reference reference})))]
    (if (:error result)
      (rpc/tool-error id (if (:fault result)
                           (if (= :data (:profile deps))
                             (rpc/json-text {:error "internal error" :code "internal_error"
                                             :reference (:reference result)})
                             (:error result))
                           ;; A refusal is an answer: hand the agent the whole
                           ;; structured finding, because "which key was wrong"
                           ;; is exactly what it needs to fix the next call.
                           (rpc/json-text (dissoc result :fault))))
      (rpc/tool-content id result (structured-content version result)))))

(defn- optional-method
  "A method the host may not serve: -32601, exactly as an unknown method,
   when the dep is absent."
  [deps k id method f]
  (if-let [g (get deps k)]
    (f g)
    (rpc/error id -32601 (str "Method not found: " method))))

(defn- dispatch-method [deps {:keys [method params id]} ctx version]
  (if (and (:methods deps) (not (contains? (:methods deps) method)))
    (when id (rpc/error id -32601 (str "Method not found: " method)))
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
    (rpc/error id -32601 (str "Method not found: " method)))))

(defn handle
  "Handles one decoded JSON-RPC message (a batch element, or the whole body of
   a non-batch request) from an authenticated caller, read under `version`.
   Returns the response as a JSON string, or nil when there is no reply (a
   notification). A non-Request object gets an -32600 Invalid Request echoing
   its :id when present."
  [deps decoded ctx version]
  (if-let [parsed (rpc/as-request decoded)]
    (dispatch-method deps parsed ctx version)
    (rpc/error (when (map? decoded) (:id decoded)) -32600 "Invalid Request")))
