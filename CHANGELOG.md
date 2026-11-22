# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/). Before 1.0, a minor version may change the API.

## [Unreleased]

## [0.1.1] - 2026-11-22

### Fixed
- Nothing thrown reaches the host's HTTP stack any more. A request whose handling throws outside a
  tool call (the host's `:tools-list`, resources, prompts) is answered -32603 with a reference; a tool
  result JSON cannot carry (a `LocalDate`, `NaN`) and an `Error` thrown by a tool (an `AssertionError`)
  are tool faults with a reference, like an exception; a failing `:authenticate` or `:rate-limit?` is a
  500 with a reference; a request without a body is a parse error. `:on-fault` receives all of them: its
  third argument is the tool's name, else the method, or `"http"`.
- A notification is never answered. `notifications/cancelled`, `notifications/progress` and any other
  message without an id got a -32601 error with HTTP 200; they now get 202 and no body, as does a
  JSON-RPC response sent to the server. A message without a string `method` is -32600.
- The client shares one `HttpClient` (per connect timeout) between connections. A one-shot `call-tool`
  used to build one per call and leave its threads to the garbage collector.

### Changed
- `initialize` answers a protocol revision it does not speak with the newest one it does (2025-06-18)
  instead of 2025-03-26, as the specification asks. A client asking for 2025-11-25 now gets `title`,
  `outputSchema` and `structuredContent`.

### Added
- Client options `:connect-timeout-ms` (documented) and `:http-client` (a host's own `HttpClient`).

## [0.1.0] - 2026-11-17

First release.

### Added
- `shuttle.http`: a Ring handler for MCP over Streamable HTTP (`POST /mcp`) and the older HTTP+SSE
  transport; protocol revisions 2024-11-05, 2025-03-26 and 2025-06-18; JSON-RPC batches; authentication,
  rate limit, body limit and Origin check injected by the host. Stateless: no server sessions.
- Two profiles: `routes` (build: tools, resources, prompts, instructions) and `data-routes` (data: tools
  only on `/mcp/data`, limits required, stable error codes).
- `shuttle.core`: the protocol without a transport.
- `shuttle.tools`: tools as data with malli schemas (JSON Schema announced, arguments decoded and checked,
  defaults, output schema and `structuredContent`, per-tool timeout, per-caller visibility);
  `data-deps` requires `:output` and `:timeout-ms`.
- `shuttle.client`: client over Streamable HTTP; every failure an `ex-info` without the credential;
  event-stream answers; session re-initialize on 404.
- `shuttle.json`: keywords keep their namespace when written.

[Unreleased]: https://github.com/rosetta-oss/mcp-shuttle/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/rosetta-oss/mcp-shuttle/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/rosetta-oss/mcp-shuttle/releases/tag/v0.1.0
