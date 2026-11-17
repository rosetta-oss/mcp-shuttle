# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/). Before 1.0, a minor version may change the API.

## [Unreleased]

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

[Unreleased]: https://github.com/rosetta-oss/mcp-shuttle/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/rosetta-oss/mcp-shuttle/releases/tag/v0.1.0
