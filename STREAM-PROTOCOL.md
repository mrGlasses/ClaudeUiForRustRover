# claude stream-json protocol, as observed

Reference for the chat panel (Part 2). Source: the Phase 6 spike against **Claude Code 2.1.312 on Windows**
(`~/.claude-meta/spike-20261030-000526.log`), plus the docs and SDK notes listed at the end. Re-run the spike after CLI upgrades.

## Launch

```
claude -p --input-format stream-json --output-format stream-json --verbose
       --include-partial-messages --permission-mode default --permission-prompt-tool stdio
       [--resume <session_id>]
```

- cwd = project root. One process = one conversation; it stays alive across turns until stdin closes.
- Pass `--permission-mode` explicitly: the user's default may be `auto`, where nothing is ever asked.
- `--include-partial-messages` adds `stream_event` lines (token streaming). Without it only whole messages arrive.
- stdin: **one JSON object per line, `\n` only**, flushed after each line.
- The IDE bridge env vars (`CLAUDE_CODE_SSE_PORT`, `ENABLE_IDE_INTEGRATION`) did **not** make `-p` connect to our bridge (0 connections).

## Host → CLI

| Message | Shape | Observed reply |
|---|---|---|
| initialize (send first) | `{"type":"control_request","request_id":"<id>","request":{"subtype":"initialize","hooks":null}}` | `control_response` success with `commands` (slash commands, with description and argumentHint) and more |
| user turn | `{"type":"user","message":{"role":"user","content":"<text>"},"parent_tool_use_id":null}` | events, ending with `result` |
| set permission mode | `{"type":"control_request","request_id":"<id>","request":{"subtype":"set_permission_mode","mode":"acceptEdits"}}` | success `{"mode":"acceptEdits"}`, then `system/status` with `permissionMode` |
| interrupt | `{"type":"control_request","request_id":"<id>","request":{"subtype":"interrupt"}}` | success `{"still_queued":[]}`, a `user` message `[Request interrupted by user]`, a `result` with `stop_reason: null` |
| answer a permission request | `{"type":"control_response","response":{"subtype":"success","request_id":"<cli id>","response":{"behavior":"allow","updatedInput":<input>}}}` | tool runs (file created) |

Deny (from SDK docs, not yet exercised): `"response":{"behavior":"deny","message":"<why>"}`. On CLI 2.1, **allow must echo `updatedInput`**.

## CLI → host

| `type` | Notes |
|---|---|
| `control_response` | Answers our control requests (matched by `request_id`). |
| `control_request` | `subtype: "can_use_tool"` with `tool_name`, `display_name`, `input`, `description` (human-readable target), `permission_suggestions` (e.g. `{"type":"setMode","mode":"acceptEdits","destination":"session"}`, `{"type":"addDirectories",...}`): the "save to" options of VS Code's permission cards. The request_id is a UUID chosen by the CLI. |
| `system` / `init` | Sent at the start of **every turn**: `cwd`, `session_id`, `tools`, model, permission mode, MCP servers, ... |
| `system` / `status` | `status: "requesting"` while waiting for the API; also carries `permissionMode` after a mode change. |
| `stream_event` | Raw API stream: `message_start`, `content_block_start`, `content_block_delta` (`text_delta`, `input_json_delta`), `content_block_stop`, `message_delta`, `message_stop` (`abandoned_blocks` after an interrupt). Fields: `event`, `session_id`, `parent_tool_use_id`, `uuid`, `api_message_id`, `ttft_ms`. |
| `assistant` | Whole message: `content` blocks `text`, `tool_use` (`id`, `name`, `input`), model, usage. |
| `user` | Tool results (`tool_result` with `tool_use_id`, plus a structured `tool_use_result`), and the interrupt marker. |
| `rate_limit_event` | Plan usage windows (five_hour / seven_day utilization and reset time): feeds the usage display. |
| `result` | End of turn: `stop_reason`, `session_id`, `total_cost_usd`, `usage`, `duration_api_ms`, `modelUsage`. |

## Behaviour notes

- Multi-turn in one process works; the session id stays the same.
- `--resume <session_id>` in a new process continues the conversation (it remembered the earlier file).
- Closing stdin after an interrupted turn exited with code 1; after a normal turn, code 0.
- No stderr output in a normal run.

## Spike v2 (2026-10-30, RustRover 2026.2.4 build RR-262.10968.211)

- **`--ide` does not attach `-p` sessions either** (0 bridge connections, before and after a turn). Conclusion: the IDE bridge is
  for interactive terminal sessions only. Panel sessions must get IDE tools another way: hand the CLI our own MCP server with
  `--mcp-config` (what VS Code's panel does with its `claude-vscode` server). Spike v3 checks this with a small loopback HTTP MCP server.
- `system/init` keys: `type, subtype, cwd, session_id, tools, mcp_servers, model, permissionMode, slash_commands,
  terminal_slash_commands, apiKeySource, claude_code_version, output_style, agents, skills, plugins, capabilities,
  analytics_disabled, product_feedback_disabled, uuid, memory_paths, original_cwd, additional_directories, messaging_socket_path,
  fast_mode_state, fast_mode_disabled_reason, per_turn_effort_active, view_mode, powershell_path`.
- `mcp_servers` entries are `{name, status}`; seen: `rustrover:connected` (the user's own RustRover MCP server, configured in
  Claude Code), `claude.ai Claude Docs:connected`, `claude.ai Google Drive:needs-auth`. The panel's MCP dialog can list these.
- **JCEF works** once `com.intellij.modules.jcef` is declared (optional dependency): `JBCefApp.isSupported() == true`.

## Spike v3 (2026-10-30, v0.12.2): IDE tools in panel sessions via our own MCP server

**Works.** `HttpMcpServer` (loopback, bearer token) passed with `--mcp-config <file>`:
- `system/init` lists `claude-ui:connected` and the tool `mcp__claude-ui__getDiagnostics` (renamed `clawed-ui` in v0.15.0).
- The CLI first POSTs `server/discover` (id `server-discover-probe-1`); our `-32601` reply is fine, it then continues.
- `initialize` asks for protocol **2025-11-25**; we echo it. Then `notifications/initialized`, `tools/list`, `tools/call`.
- Calling our tool triggers a normal `can_use_tool` permission request (`tool_name` = `mcp__claude-ui__getDiagnostics`),
  so panel MCP tools go through the same permission cards. Allow → the call reached the IDE in ~4 ms, result `[]`
  (no open file had problems), Claude answered `0`.
- The config file holds the token: create it owner-only in the temp folder and delete it when the session ends.

## Sources

- Headless docs: https://code.claude.com/docs/en/headless
- can_use_tool not sent bug: https://github.com/anthropics/claude-code/issues/34046
- updatedInput required: https://github.com/openclaw/openclaw/pull/98665
- JCEF moved out of the core in 2026.2: https://github.com/okevino47/stormies/issues/4 , https://github.com/rajbos/ai-engineering-fluency/pull/2345
