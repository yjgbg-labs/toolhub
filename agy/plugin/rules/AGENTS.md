# Groot Code Mode

Groot capabilities (Chrome automation, remote/local Shell, SQLite, Schedule, Widget, Wine, server FS, outbound HTTP, persistent State) are exposed via the `groot` MCP server using **two tools**:

1. **`search`**: Discover Groot GraalJS SDK TypeScript signatures from live `/openapi.json`.
   - Arguments: `{"namespace"?: string, "keyword"?: string}`
   - Examples: `{"namespace": "chrome.page", "keyword": "click"}`, `{"namespace": "shell"}`, `{}` (compact full index)
2. **`execute`**: Run an isolated GraalJS async script on Groot Server with `tools.*` and `ctx` bound.
   - Arguments: `{"graalScript": string, "timeout"?: string}` (default timeout `"30s"`, e.g. `"10s"`, `"2m"`)

## Usage Guidelines

- **Search before calling unfamiliar commands**: Always inspect exact TypeScript signatures with `search` before guessing parameter names or `{body}` wrapping.
- **Batch related operations in one `execute` script**: Use top-level `await`, `Promise.all`, loops, and filtering inside a single GraalJS script to minimize round trips and keep context concise.
- **Output**: Both `console.log(...)` and top-level `return value` are captured and returned.
- **SDK Contract**:
  - Every `tools.<ns>.<cmd>(...)` returns `Promise<{ status, headers, body }>` and rejects with `ApiError` on non-2xx responses.
  - Body-only commands take the payload directly; commands with path/query/header parameters plus a body pass `{ ...params, body }`.
- **Session Context (`ctx`)**: `ctx` is automatically injected per call with `{ conversationId, cwd, workspacePaths }`.
- **Sandbox Limits**: Isolated GraalJS context (4 MiB JS heap, no Node/Bun modules). Use `tools.http`, `tools.fs.*`, and `tools.shell.execute` for external I/O.
