# mcp-shuttle

MCP ([Model Context Protocol](https://modelcontextprotocol.io/)) server and client for Clojure. The server is a plain Ring
handler; the client uses the JDK's HttpClient.

Status: early. Server (HTTP transport, protocol core, tools with malli schemas) and client work and
are tested, in two profiles; sessions for server push and protocol 2025-11-25 are next.

## Install

`deps.edn`, as a git dependency (the SHA is the tag's commit, listed on the release page):

```clojure
io.github.rosetta-oss/mcp-shuttle {:git/tag "v0.1.0" :git/sha "<7+ characters of the tag's commit>"}
```

Needs Java 21 or later. Changes are in [CHANGELOG.md](CHANGELOG.md).

## Layers

| Namespace | What |
|---|---|
| `shuttle.core` | The protocol without a transport: a decoded JSON-RPC message, the caller's context and the revision in; the JSON reply out. |
| `shuttle.tools` | Tools as data with malli schemas: announced as JSON Schema, arguments decoded and checked before the handler runs, optional output schema and `structuredContent`, per-tool timeout, per-caller visibility. |
| `shuttle.http` | Ring handler: Streamable HTTP and HTTP+SSE, authentication, rate limit, body limit, Origin check, batches. |
| `shuttle.client` | Client over Streamable HTTP. |

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
(require '[shuttle.http :as mcp] '[shuttle.tools :as tools])

(def my-tools
  [{:name "echo"
    :description "Echoes its text"
    :input [:map [:text :string]]
    :output [:map [:said :string]]
    :handler (fn [ctx {:keys [text]}] {:said text})}])

(def handler
  (mcp/routes
   (merge
    (tools/deps my-tools)          ; :tools-list and :call-tool
    {:authenticate (fn [request]
                     (when-let [caller (lookup-key (mcp/bearer request))]
                       {:id (:id caller) :ctx caller}))
     :server-info {:name "my-service" :version "1.0.0"}
     :unauthorized-message "Send your service key as a Bearer token."
     :sse-writer (fn [write!] (ring-streaming-body write!))})))
```

`routes` returns nil for any other path, so it composes with a host's own routes. See the docstrings
of `shuttle.http` and `shuttle.tools` for every injected function and tool option. A tool refuses by
returning `{:error …}` (sent to the agent as JSON) and fails by throwing.

## Two profiles

| | `routes` (build) | `data-routes` (data) |
|---|---|---|
| For | people and their agents (Claude Code, agent builders) | programs: other services, batch jobs |
| Path | `/mcp` (+ `/mcp/sse`, `/mcp/messages`) | `/mcp/data`, POST only |
| Offers | tools, resources, prompts, instructions | tools only |
| Limits | optional | `:rate-limit?` and `:max-body-bytes` required (`mcp/rate-limiter`) |
| Tools | `tools/deps` | `tools/data-deps`: every tool needs `:output` and `:timeout-ms` |
| Failures | readable text | JSON with a stable `code`: `invalid_arguments`, `unknown_tool`, `timeout`, `internal_error` (+ `reference`), or the tool's own |

A host can mount both, with different tool sets and limits.

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
