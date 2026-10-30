# Clawed UI — sketch

A JetBrains plugin that launches the user's own `claude` CLI and gives it IDE features (diff review,
selection, diagnostics) by implementing the CLI's IDE-integration protocol itself.
The official plugin (`com.anthropic.code.plugin`) is **optional**, detected at runtime, never a `<depends>`.

> Status: uncompiled sketch. The Kotlin was written against remembered IntelliJ Platform APIs and a
> community-documented protocol. Treat every `SPIKE`/`TODO` comment as a real open item.

## Shape

```
 RustRover (this plugin)                                  claude CLI (user's install + login)
 ┌──────────────────────────────┐   lock file            ┌─────────────────────────┐
 │ MetaClaudeService            │──~/.claude/ide/<port>──▶ discovers / is told port │
 │  ├─ IdeBridgeServer (WS)  ◀──┼── ws://127.0.0.1:<port> ┤ sends ide_connected,    │
 │  │    └─ McpDispatcher       │   x-claude-code-ide-    │ tools/call openDiff...  │
 │  │         └─ IdeTool x N    │   authorization: token  └─────────────────────────┘
 │  ├─ LockFile / LockScanner   │
 │  ├─ ClaudeLauncher (PTY)─────┼── env CLAUDE_CODE_SSE_PORT, ENABLE_IDE_INTEGRATION=true
 │  └─ OfficialPluginDetector   │
 └──────────────────────────────┘
```

## Official plugin: optional, three modes

| Mode | What we do | When |
|---|---|---|
| `BUILTIN` (default) | Start our bridge, write our lock file, launch CLI pointed at our port | Always works; no dependency |
| `OFFICIAL` | No bridge, launch CLI plain, official plugin owns IDE features | User prefers it and it is installed + enabled |
| `AUTO` | `OFFICIAL` if installed + enabled, else `BUILTIN` | Zero-config |

Falls back to `BUILTIN` whenever the official plugin is missing or disabled.
Health check 8 s after launch: in `BUILTIN` we know whether the CLI connected (our WS `onOpen`);
in `OFFICIAL` we look for a live lock file covering the project and, if none, offer
"Use built-in bridge and restart". That is the "avoid connection problems" requirement, without the hard dependency.

Coexistence: our lock file uses a distinct `ideName`, so `/ide` in an external terminal lists two entries.
Sessions we launch are bound to our port by env var, so they never pick the wrong one.

## Protocol cheat sheet (coder/claudecode.nvim PROTOCOL.md + official docs)

- Server: WebSocket on `127.0.0.1`, OS-assigned port. JSON-RPC 2.0, MCP `2025-03-26`.
- Lock file `~/.claude/ide/<port>.lock` (or `$CLAUDE_CONFIG_DIR/ide/`), dir 0700, file 0600:
  `{ pid, workspaceFolders[], ideName, transport:"ws", runningInWindows, authToken }` — token = 32 hex chars (128-bit CSPRNG), fresh per start.
- Auth: CLI sends `x-claude-code-ide-authorization: <authToken>` on the handshake; reject otherwise.
- CLI matches its real cwd against `workspaceFolders` → write `toRealPath()` values.
- IDE → CLI notifications: `selection_changed`, `at_mentioned`.
- CLI → IDE: `initialize`, `tools/list`, `tools/call`, and an `ide_connected` notification.
- Tools: `openFile`, `openDiff` (**blocking**), `getCurrentSelection`, `getLatestSelection`, `getOpenEditors`,
  `getWorkspaceFolders`, `getDiagnostics`, `checkDocumentDirty`, `saveDocument`, `close_tab` (snake_case), `closeAllDiffTabs`, `executeCode` (Jupyter only, skip).
- `openDiff` replies only after the user decides: `FILE_SAVED` or `DIFF_REJECTED` (`TAB_CLOSED` if the tab was closed).
  `close_tab`/`closeAllDiffTabs` must also resolve pending diffs or the CLI hangs.
- The model only sees `getDiagnostics` (and `executeCode`) from the IDE server; the rest serve the CLI/UI.

Implemented in the sketch: `getWorkspaceFolders`, `getOpenEditors`, `getCurrentSelection`, `openFile` (partial),
`openDiff` (flow done, Accept/Reject UI is a TODO), `getDiagnostics` (stub). Not yet: the rest.

## Spikes, ordered by risk

1. **Headless attach.** Does the bridge attach under `claude -p --input-format stream-json --output-format stream-json --verbose`?
   If not, a custom chat UI can't get diff/selection and the plugin must embed the interactive PTY session instead. Decide this before building any UI.
2. **Handshake capture.** Log request headers once (subprotocol? version echo in `initialize`?) and compare with `McpDispatcher.initializeResult`.
3. **`openDiff` fidelity.** What the CLI does when the user edits the proposed side before accepting (write edited text? extra content item?).
4. **Notification timing.** A GitHub issue reports IDE notifications sent immediately after `ide_connected` are dropped; delay the first `selection_changed` (tune the delay).
5. **Diagnostics for Rust.** Map IDE highlights (rust-analyzer / clippy via the Rust plugin) to the `getDiagnostics` shape.
6. **Token delivery drift.** A newer CLI debug log mentions a file-descriptor auth channel (`CLAUDE_CODE_WEBSOCKET_AUTH_FILE_DESCRIPTOR`); the lock-file path is what community plugins use today. Re-test on each CLI release.

## Not verified

- Every IntelliJ API signature in `IdeTools.kt`/`OfficialPlugin.kt` (DiffManager hints, `CONTEXT_ACTIONS`, `findEnabledPlugin`).
- Gradle plugin/RustRover coordinates and versions in `build.gradle.kts`.
- `OFFICIAL` mode relies on the CLI's own discovery; whether it auto-connects or needs `/ide` / `--ide` is untested.
- The protocol is reverse-engineered, not a published spec: pin tested CLI versions and show a clear "protocol mismatch" message.

## Sources

- coder/claudecode.nvim PROTOCOL.md — https://raw.githubusercontent.com/coder/claudecode.nvim/main/PROTOCOL.md
- Official JetBrains integration docs — https://code.claude.com/docs/en/jetbrains
- Repro server + notification-drop report — https://github.com/anthropics/claude-code/issues/99268
- Lock file fields in official plugin (`runningInWindows` etc.) — https://github.com/anthropics/claude-code/issues/14421
- FD-auth debug log / lock-file bug — https://github.com/anthropics/claude-code/issues/36284
- "Model only sees getDiagnostics/executeCode" note — https://packages.sublimetext.io/packages/Claude%20Code%20IDE
- Other implementations: NetBeans (Saljack/claude-code-netbeans), Visual Studio (dennisMarcelo/ClaudeCodeVS), Zed patch
