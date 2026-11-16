# mcp-shuttle

MCP ([Model Context Protocol](https://modelcontextprotocol.io/)) server and client for Clojure. The server is a plain Ring
handler; the client uses the JDK's HttpClient.

Status: early. The HTTP server side and the client work and are tested; the protocol core, tool
definitions with malli schemas and the `data` profile are next.

## What it does

- Streamable HTTP (`POST /mcp`, protocol 2025-03-26 and 2025-06-18) and the older HTTP+SSE
  transport (`GET /mcp/sse`, `POST /mcp/messages`, 2024-11-05).
- Version negotiation, JSON-RPC batches, `tools`, `resources`, `prompts`, `instructions`.
- No sessions on Streamable HTTP: every request stands alone, so restarts and several instances
  need nothing special.
- Everything a host decides is injected: who the caller is (`:authenticate`), which tools that
  caller sees (`:tools-list`, given the caller's context), what a call does (`:call-tool`), rate
  limiting, body size limit, fault logging.
- A tool that refuses (`{:error …}`) and a tool that throws are kept apart: the first becomes an
  `isError` result the agent can act on, the second a generic message with a reference; the
  exception text never reaches the client.

## Use

```clojure
(require '[shuttle.http :as mcp])

(def handler
  (mcp/routes
   {:authenticate (fn [request]
                    (when-let [caller (lookup-key (mcp/bearer request))]
                      {:id (:id caller) :ctx caller}))
    :tools-list (fn [ctx] [{:name "echo" :description "Echoes its text"
                            :inputSchema {:type "object" :properties {:text {:type "string"}}}}])
    :call-tool (fn [ctx tool args] (case tool "echo" {:said (:text args)}))
    :server-info {:name "my-service" :version "1.0.0"}
    :unauthorized-message "Send your service key as a Bearer token."
    :sse-writer (fn [write!] (ring-streaming-body write!))}))
```

`routes` returns nil for any other path, so it composes with a host's own routes. See the docstrings
of `shuttle.http` for every injected function.

## Client

```clojure
(require '[shuttle.client :as mcp-client])

;; one call: initialize, call, close
(mcp-client/call-tool {:url "http://host:port/mcp" :bearer key :timeout-ms 180000} "templates" {:limit 200})

;; several calls
(let [c (mcp-client/connect {:url "http://host:port/mcp" :bearer key})]
  (try (mcp-client/list-tools c) (finally (mcp-client/close c))))
```

Every failure throws an `ex-info` (HTTP status, JSON-RPC error, `isError`, unreadable answer,
timeout, no connection); nothing is printed and nothing silently returns nil. Event-stream answers
and server sessions are handled, including initializing again once when a server forgets a session.

## Develop

```
clojure -M:test
```

The test `the-wire-knows-no-business` scans the library's source: it must never learn a host's
vocabulary.

## License

Copyright (c) 2026 mcp-shuttle contributors.

Eclipse Public License 2.0, see [LICENSE](LICENSE). Code taken from
[PluMCP](https://github.com/plumce/plumcp) (EPL-1.0, Shantanu Kumar) keeps its original copyright
notice and names its source in the file header.
