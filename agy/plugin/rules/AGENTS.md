# Groot Code Mode

Groot capabilities are discovered dynamically from live `/openapi.json` (summarized at session start) and exposed via the `groot_codemode` MCP server using **two tools**:

1. **`search`**: Discover Groot GraalJS SDK TypeScript signatures from live `/openapi.json`.
   - Arguments: `{"namespace"?: string, "keyword"?: string}`
   - Pass `namespace` and/or `keyword` to inspect matching signatures, or `{}` to view the compact full index.
2. **`execute`**: Run an isolated GraalJS async script on Groot Server with `tools.*` and `ctx` bound.
   - Arguments: `{"code": string, "timeout"?: string}` (default timeout `"30s"`, e.g. `"10s"`, `"2m"`)

## Usage Guidelines

- **Search before calling unfamiliar commands**: Always inspect exact TypeScript signatures with `search` before guessing parameter names or `{body}` wrapping.
- **Batch related operations in one `execute` script**: Use top-level `await`, loops, and filtering inside a single GraalJS script to minimize round trips and keep context concise. When running multiple concurrent `tools.*` calls, prefer `Promise.allSettled` over `Promise.all` so individual call failures do not abort and discard the remaining results.
- **Keep `execute` output compact (< 8 KB)**: AGY automatically spills MCP tool outputs exceeding ~10 KB to a file, forcing an extra `read_file` round trip. Always filter, aggregate, project only needed fields, and summarize logs inside the GraalJS script before returning or logging instead of dumping raw logs or full objects.
- **Output**: Both `console.log(...)` and top-level `return value` are captured and returned.
- **SDK Contract**:
  - Every `tools.<ns>.<cmd>(...)` returns `Promise<{ status, headers, body }>` and rejects with `ApiError` on non-2xx responses.
  - Body-only commands take the payload directly; commands with path/query/header parameters plus a body pass `{ ...params, body }`.
- **Session Context (`ctx`)**: `ctx` is automatically injected per call with `{ conversationId, cwd, workspacePaths }`.
- **Sandbox Limits**: Isolated GraalJS context (4 MiB JS heap, no Node/Bun modules). Use the discovered `tools.*` commands for external I/O.
