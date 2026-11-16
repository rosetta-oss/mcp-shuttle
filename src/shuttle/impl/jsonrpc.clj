;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.impl.jsonrpc
  "JSON-RPC 2.0 + MCP tool-result envelopes. Pure: observability is an
   injected hook at the transport."
  (:require [shuttle.json :as json]))

(def parse-error
  "Sentinel returned by parse-json when the body isn't valid JSON at all — a
   genuine -32700 Parse error, distinct from valid JSON that isn't a Request."
  ::parse-error)

(defn parse-json
  "Decode body-str as JSON, returning the decoded value (a map for an object, a
   seq for an array, a scalar otherwise), or the `parse-error` sentinel when the
   body isn't valid JSON."
  [body-str]
  (try
    (json/read body-str)
    (catch Exception _ parse-error)))

(defn parse-error?
  "True when x is the parse-error sentinel returned by parse-json."
  [x]
  (= parse-error x))

(defn as-request
  "Coerce an already-decoded JSON value to a JSON-RPC 2.0 request map
   {:method :params :id}, or nil when it isn't a well-formed Request (e.g. a
   non-object, or an object without :jsonrpc \"2.0\") — an -32600 Invalid Request."
  [m]
  (when (and (map? m) (= "2.0" (:jsonrpc m)))
    {:method (:method m) :params (:params m) :id (:id m)}))

(defn success [id result]
  (json/write {:jsonrpc "2.0" :id id :result result}))

(defn error [id code message]
  (json/write {:jsonrpc "2.0" :id id :error {:code code :message message}}))

(defn tool-content
  "MCP tools/call success result. A string is sent as-is; anything else is
   JSON-encoded into the single text block. With `structured` (a map), the
   same value also goes out as `structuredContent` (MCP 2025-06-18) — the
   text block stays first and unchanged, for every client that reads only it."
  ([id content] (tool-content id content nil))
  ([id content structured]
   (success id (cond-> {:content [{:type "text"
                                   :text (if (string? content) content (json/write content))}]}
                 (map? structured) (assoc :structuredContent structured)))))

(defn tool-error
  "MCP tools/call error result (isError true)."
  [id message]
  (success id {:content [{:type "text" :text message}] :isError true}))
