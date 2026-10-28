# Claude UI for RustRover — action plan

Goal: **full parity with the Claude Code VS Code extension**, plus Rust-specific extras, using the user's own `claude` CLI.
The official JetBrains plugin stays optional. The feature-by-feature checklist is in **PARITY.md**.

Status on 2026-10-27: the IDE bridge works end to end on Windows with Claude Code 2.1.311.
`bridge.log` confirmed the handshake (auth header, `mcp` subprotocol), `initialize`, `ide_connected` and `tools/list`.
Everything below is **not started** unless marked otherwise.

| Phase | Status |
|---|---|
| 0 Groundwork | ☑ Done (v0.5.0: CI green, version logged, connected as "RustRover (Claude UI)") |
| 1 Diff review | ☑ Done (v0.6.2): accept, accept with your edits, reject and close-without-deciding tested on 2026-10-27 |
| 2 Selection and context | ☑ Done (v0.7.0): selection reaches Claude, Alt+K inserts the right lines (tested 2026-10-27) |
| 3 Diagnostics | **Code written (v0.8.0), waiting for CI + your install test** |
| (5.1, early) Terminal auto-connect | **Code written (v0.8.1): `claude` in RustRover terminals connects without /ide; waiting for test** |
| 4–16 | Not started |

## Two halves, like the VS Code extension

| | Terminal mode (`useTerminal`) | Chat panel (default in VS Code) |
|---|---|---|
| What runs | Interactive `claude` in a terminal | `claude -p --input-format stream-json --output-format stream-json` driven by our UI |
| IDE features come from | Our WebSocket bridge (`/ide`) | Control messages in the stream, plus an MCP server we hand the CLI (VS Code does the same with its `claude-vscode` server) |
| Phases | 0–5 | 6–14 |

The second row changes the old "headless attach" risk. The chat panel doesn't need the `/ide` bridge to attach in headless mode.
It can pass the CLI our own MCP server (`--mcp-config`) for diagnostics and other IDE tools, which is how the VS Code panel works.
Phase 6 verifies this before any UI is built.

## How every step is delivered and tested

There's no local JDK, so each phase goes through this loop:
1. The code changes go into the repo (pushed by you, or by Claude if GitHub gets linked to the session).
2. GitHub Actions builds the plugin. The **plugin-zip** artifact is the installable file.
3. You install it (uninstall the old version first, restart), run the phase's "done when" checks, and send `bridge.log` when something fails.

Rule: **one phase (or sub-phase) per push**, so a failure points at one change.

---

## Part 1 — Bridge and terminal mode

### Phase 0 — Groundwork
| # | Task |
|---|---|
| 0.1 | Bump `version` on every push and log it at bridge start (shows whether the installed build is the new one) |
| 0.2 | CI: `verifyPlugin`, unit tests, versioned artifact name |
| 0.3 | Protocol test harness: a unit test playing the CLI side (token, `mcp` subprotocol, `initialize`, `tools/list`, `tools/call`) |
| 0.4 | Final plugin name, id and vendor (the id is permanent once published) |

Done in v0.5.0: `version` + log line (0.1); CI runs `test buildPlugin`, uploads a test report on failure, and runs `verifyPlugin` as a non-blocking job (0.2);
`McpDispatcherTest` and `BridgeServerTest` play the CLI side (0.3); name **Claude UI for RustRover**, id `io.github.mrglasses.claudeui`,
vendor mrGlasses, `/ide` label **RustRover (Claude UI)** (0.4, still changeable until the first Marketplace release).
Also: `CLAUDE.md` with working rules, `.gitignore`, removed the duplicate service registration from `plugin.xml`.

**Done when:** CI is green and the first log line shows the new version.

### Phase 1 — Diff review (bridge)
| # | Task |
|---|---|
| 1.1 | Whole-file Accept / Reject (`FILE_SAVED` / `DIFF_REJECTED`) |
| 1.2 | Closing the window without a decision replies `TAB_CLOSED` (the CLI never hangs) |
| 1.3 | Editing the proposed side: Accept saves your text and Claude is told you changed it |
| 1.4 | **Per-change** Accept/Reject buttons under each change (skipped above 100 changes), plus Accept/Reject Change at Cursor actions |
| 1.5 | `close_tab`, `closeAllDiffTabs`, `checkDocumentDirty`, `saveDocument` |
| 1.6 | Writes go through the IDE (Document + VFS) so undo and open editors stay in sync; new and deleted files handled |

**Done when:** in Manual mode, an edit by Claude opens the diff, and whole-file accept, whole-file reject, per-change accept/reject and closing the window all behave correctly.

Done in v0.6.0 (`tools/DiffTools.kt`, `tools/DocumentTools.kt`, `tools/PendingDiffs.kt`):
- Diff opens as an editor tab; the right side is editable. Accept/Reject in the diff toolbar and in a banner above it.
- Accept replies `FILE_SAVED` + final text (byte-exact when unchanged, CRLF kept). **The CLI writes the file itself**: v0.6.1 also wrote it and the edit was applied twice, so v0.6.2 only saves unsaved IDE changes first and refreshes the file afterwards.
- Reject → `DIFF_REJECTED`. **Closing the tab without deciding → `DIFF_REJECTED`** (the community protocol only defines SAVED/REJECTED for openDiff).
- `close_tab` / `closeAllDiffTabs` close our tabs and answer pending calls with `TAB_CLOSED`. Project close answers everything as rejected.
- Per-change: the diff viewer's own arrows copy one change back from the current version (= reject that change). Dedicated per-change buttons and "Accept/Reject Change at Cursor" actions: later.
- `checkDocumentDirty`, `saveDocument`. Unit tests for the once-only reply rule and line separators.

### Phase 2 — Selection and context (bridge)
| # | Task |
|---|---|
| 2.1 | `selection_changed` (debounced) and open-file context, with a delay after `ide_connected` (known dropped-notification bug) |
| 2.2 | Alt+K → `at_mentioned` (`@file#5-10`), plus a "Send to Claude" editor action |
| 2.3 | `getLatestSelection`, `openFile` with `startText`/`endText` |
| 2.4 | `attachSelection` / `attachOpenFile` honored; selections from excluded or git-ignored files withheld (`respectGitIgnore`) |
| 2.5 | Windows path normalization everywhere |

**Done when:** after selecting code, "what did I select?" gets a correct answer, and Alt+K inserts a mention.

Done in v0.7.0 (`tools/SelectionTracker.kt`, `ContextActions.kt`, `protocol/ContextJson.kt`):
- `selection_changed` on selection, cursor and tab changes: debounced 200 ms, starts 500 ms after `ide_connected`, duplicates skipped.
- Files that are git-ignored, excluded or outside the project: path and cursor only, never the text.
- Alt+K and editor right-click "Send Selection to Claude" → `at_mentioned`. Lines are **0-based** (confirmed: lines 38–45 in the editor showed as `#38-45`).
- `getLatestSelection`; `getCurrentSelection` uses the same reader; `openFile` with `startText`/`endText`/`selectToEndOfLine`/`makeFrontmost`.
- All paths sent as `C:\...`, file URLs as `file:///C:/...`.
- Not yet: `attachSelection` / `attachOpenFile` switches (they need the settings page, Phase 4).

### Phase 3 — Diagnostics
| # | Task |
|---|---|
| 3.1 | `getDiagnostics` from IDE highlights (one file or all), severity, range and source mapped |
| 3.2 | Check what the Rust plugin reports (its analysis, external linter / clippy when enabled) |

**Done when:** break some Rust code, and Claude lists the same errors RustRover underlines.

Done in v0.8.0 (`GetDiagnostics` in `tools/IdeTools.kt`, `protocol/DiagnosticsJson.kt`):
- Reads the IDE's own highlighting (errors, warnings, weak warnings → Error / Warning / Information), so it includes
  Rust analysis and clippy / cargo check results when RustRover's external linter is on.
- Without `uri`: every open file with problems. With `uri` (`file:///c%3A/...`, `file:///C:/...` or a plain path): that file.
- Limit: the IDE only analyzes open files, so a requested file that isn't open may come back empty.
- The log now names each tool call (`tool=getDiagnostics`) and counts files/items, never the content.

### Phase 4 — Settings page, modes, launcher
| # | Task |
|---|---|
| 4.1 | Settings page with **all** VS Code settings that apply (list in PARITY.md §L) plus ours (integration mode, `claude` path) |
| 4.2 | Official plugin status shown; Built-in / Official / Auto modes working |
| 4.3 | Windows launcher: find `claude.exe` / `claude.cmd`, `claudeProcessWrapper`, `environmentVariables`, clear error when missing |
| 4.4 | Health-check notifications |

**Done when:** each setting persists across restarts, mode switching shows or hides our `/ide` entry, and a bad `claude` path gives a readable error.

### Phase 5 — Terminal mode (= VS Code `useTerminal`)
| # | Task |
|---|---|
| 5.1 | "Claude" tool window with an embedded terminal running `claude`, already bound to our bridge |

Already in v0.8.1: every RustRover terminal tab gets `CLAUDE_CODE_SSE_PORT` + `ENABLE_IDE_INTEGRATION` (terminal customizer, `order="last"`),
so `claude` there connects by itself. External and WSL terminals still need `/ide`.
| 5.2 | Open in Terminal, Open in New Tab (more terminal tabs), Focus Input (Ctrl+Esc) |

**Done when:** Tools > Start Claude Session opens a working, connected session inside RustRover. **This is the first daily-usable release.**

---

## Part 2 — Chat panel (VS Code parity)

### Phase 6 — Spikes and foundation (decision gate)
| # | Spike / task | Decides |
|---|---|---|
| 6.1 | Drive `claude -p` with stream-json both ways: send prompts, read every event type, interrupt, resume (`--resume`) | Whether the panel is feasible on this CLI |
| 6.2 | Permission prompts over the stream (the Agent SDK approach: control requests answered by the host) | Whether we can show our own permission cards |
| 6.3 | Pass our MCP server with `--mcp-config` (diagnostics, open file) | IDE tools in the panel without `/ide` |
| 6.4 | Mid-session control: change permission mode, model, effort, thinking | Whether those pickers can be live |
| 6.5 | UI technology: JCEF (embedded Chromium web view, like VS Code's webview) vs native Swing/Compose | Rendering stack (recommendation: JCEF, for Markdown, LaTeX and the many dialogs) |
| 6.6 | Frontend build in CI (npm bundle packed into the plugin) | Build pipeline |
| 6.7 | Write down every stream event and control message seen, as `STREAM-PROTOCOL.md` | Reference for phases 7–13 |

**Done when:** a throwaway panel can send a prompt, render the streamed answer, approve one permission, and stop a turn.
If 6.1 or 6.2 fails, the panel waits, and terminal mode (Phase 5) remains the product.

### Phase 7 — Chat core (PARITY §B, §E)
Sending and receiving, streaming Markdown, code blocks, tool-call rows, thinking blocks (Ctrl+O), to-do list, queued messages,
Shift+Enter / `useCtrlEnterToSend`, Stop / Esc, timestamps, scroll setting, Focus view (Ctrl+Alt+F), copy response, `/copy`, spinner verbs,
LaTeX math, files Claude sends, sign-in screen, the `/` command menu.

**Done when:** a normal coding conversation works fully in the panel, without a terminal.

### Phase 8 — Permissions and review (PARITY §D)
Permission mode indicator and switching, permission cards with "save to" destinations, diffs from the panel (reusing Phase 1's viewer with per-change review),
plan review as a Markdown document with inline comments, `/plan` variants, Claude's questions (multiple choice + Other), `initialPermissionMode`,
Bypass only when `allowDangerouslySkipPermissions`, `autosave`.

**Done when:** in each mode (Auto, Manual, Plan, Edit automatically), approving, rejecting and plan feedback work from the panel.

### Phase 9 — Context and mentions (PARITY §C)
Selection and open-file chips with X, `@` fuzzy file and folder search, Alt+K, `@terminal:name` (JetBrains terminal buffers),
pasted images, Shift+drag files, attachments removable, ignore rules, `@browser`.

**Done when:** each kind of context reaches Claude, which you check by asking about it.

### Phase 10 — Sessions (PARITY §F)
History (search, by time), resume, titles, rename, archive and auto-archive, groups, filters, several conversations in tabs and windows,
status dots, reopen closed (Ctrl+Shift+T inside the panel), continue after reload, "still open somewhere else" and "not reopened" guards,
`/export`, Web tab (cloud and Remote Control sessions).

**Done when:** sessions survive an IDE restart, and history, groups and filters behave like VS Code's.

### Phase 11 — Checkpoints, agents, background work (PARITY §G, §H)
Fork from here, rewind code, fork + rewind; agent map (tree, status, time, tokens, transcript, stop), background tasks with live output,
Run in background, `/tasks`.

**Done when:** rewinding restores files, and a background subagent can be watched and stopped from the map.

### Phase 12 — Model, usage, status (PARITY §B, §I)
Model picker with Effort and Ultracode, extended thinking toggle, context indicator, prompt-cache clock, `/usage` dialog (bars, recommendations + Fix, attribution),
`/status`, sign out, Report a problem.

**Done when:** model and effort change mid-session, and the usage dialog matches the CLI's numbers.

### Phase 13 — Customize dialogs (PARITY §I)
Slash commands browser and `/skills` visibility, output styles (+ build custom), hooks editor, permissions editor, memory, instructions (CLAUDE.md),
sandbox, Chrome, `/mcp` dialog, `/plugins` dialog (scopes, config form, marketplaces), Remote Control toggle.

**Done when:** each dialog's changes show up identically in a terminal `claude` session (they share the same config files).

### Phase 14 — IDE polish (PARITY §A, §K, §L, §M)
Tool window / editor toolbar / status bar entry points, panel placement (`preferredLocation`), editor-split behavior, keymap (resolve the JetBrains conflicts listed in PARITY §K),
`/btw` side panel, bookmarks, onboarding page, URI handler (open with prompt/session, install-plugin links), screen reader support, third-party provider mode, Show Logs.

**Done when:** every row in PARITY.md is ☑ or explicitly marked Skip.

---

## Part 3 — Beyond parity and release

### Phase 15 — Rust extras (the differentiator)
"Ask Claude to fix" on compiler errors, `cargo check` / `clippy` / `test` failures to the session, crate and workspace context, optional Rust toolchain environment (the `usePythonEnvironment` analogue).

### Phase 16 — Hardening and release
Several windows at once, clean shutdown and stale lock sweep, CLI version check with a "protocol mismatch" message, security review (loopback only, no token in logs, lock file permissions on Windows),
performance on long conversations, README, license, screenshots, changelog, Marketplace signing, verifier and publishing.

---

## Decisions needed from you

| When | Decision |
|---|---|
| Before Phase 0 | Final plugin **name and id** (permanent once published) |
| Phase 6 | After the spikes: build the chat panel, or stay terminal-only for now |
| Phase 6.5 | UI stack (recommendation: JCEF web view) |
| Phase 14 | Keymap choices where VS Code keys clash with JetBrains (Ctrl+N, Ctrl+Shift+T, Ctrl+Alt+F, Ctrl+Shift+Esc) |
| Phase 16 | Publish on the Marketplace or keep private; **license** |
| Any time | Optional: link GitHub (claude.ai Settings > Connectors) so Claude pushes and you only install and test |

## Rough size

| Part | Phases | Size |
|---|---|---|
| 1 Bridge and terminal mode | 0–5 | medium (days to a couple of weeks), **usable product at the end** |
| 2 Chat panel parity | 6–14 | large (VS Code's panel is a big product; weeks to months) |
| 3 Extras and release | 15–16 | medium |

Phases 1–4 are independent after Phase 0. Phases 7–13 depend on Phase 6 but are mostly independent of each other.

## Known gaps right now

- Start Claude Session launches the CLI invisibly, so use your own terminal + `/ide` until Phase 5.
- `openDiff` has no Accept/Reject, so **don't let Claude edit files through our entry until Phase 1**.
- `getDiagnostics` returns an empty list. Nothing sends selection changes. The integration mode is hard-coded, and there's no settings page.

## Sources

- VS Code extension feature reference: https://code.claude.com/docs/en/vs-code
- JetBrains integration docs: https://code.claude.com/docs/en/jetbrains
- IDE protocol (community, reverse-engineered): https://raw.githubusercontent.com/coder/claudecode.nvim/main/PROTOCOL.md
- Dropped notifications after `ide_connected`: https://github.com/anthropics/claude-code/issues/99268
