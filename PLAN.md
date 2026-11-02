# Clawed UI — action plan

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
| 3 Diagnostics | ☑ Done (v0.8.0): Claude got the same error RustRover underlined, on the right line (tested 2026-10-28) |
| (5.1, early) Terminal auto-connect | ☑ Done (v0.8.1): `claude` in a new RustRover terminal connects without /ide (tested 2026-10-28) |
| 4 Settings, modes, launcher | ☑ Done (v0.9.0): Test finds claude, mode switch stops/starts all windows, attachSelection off hides text, settings survive restart (tested 2026-10-28) |
| 5 Terminal mode | ☑ Done (v0.10.1): Claude tab, several sessions, Alt+Shift+K, no error on first open (tested 2026-10-29). **Part 1 complete** |
| 6 Spikes | ☑ Done (v0.13.0): all spikes pass; web panel builds in CI and loads securely in RustRover (tested 2026-10-29) |
| 7 Chat core | ☑ Done (7a, 7b, 7c; v0.17.1 tested 2026-10-31). `focusView` setting left for Phase 14 |
| Rename | ☑ Done (v0.15.0 tested 2026-10-30: Clawed UI in log, settings and `/ide`) |
| 8 Permissions and review | ☑ Done (8a v0.18.1, 8b v0.19.1, tested 2026-10-31) |
| Precheck | ☑ v0.19.2: compile, tests and plugin verifier run before every hand-over (`tools/precheck/`) |
| 9 Context and mentions | ☑ Done (9a v0.20.0, 9b v0.21.2, tested 2026-10-31). `@browser` moved to Phase 15 |
| 10 Sessions | ☑ Done (10a, 10b v0.23.3; 10c v0.24.2 tested 2026-11-01). Web tab (cloud / Remote Control sessions) moved to Phase 15 |
| 11 Checkpoints, agents | ☑ Done (11a v0.25.0, 11b v0.26.0, tested 2026-11-01) |
| 12 Model, usage, status | ☑ Done (12a v0.27.0, tooltips v0.27.1, 12b v0.28.0; tested 2026-11-01; sign out not tried on purpose) |
| 13 Customize | ☑ Done (13a–13d, v0.29.0–v0.32.0, tested 2026-11-02). Remote Control toggle moved to Phase 15 (with the Web tab), plugin install links to Phase 14 (URI handler) |
| 14 IDE polish | **14a (entry points, status bar, location, Ctrl+N) v0.33.0: waiting for your test**. 14b (prompt-box and transcript settings, `/btw`, bookmarks, onboarding), 14c (URI handler, accessibility, providers, environment) next |
| 15–16 | Not started |

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
- Note from testing: asked vaguely ("check the diagnostics"), Claude may run `cargo check` instead; asked for `mcp__ide__getDiagnostics`, it uses the tool.
- v0.8.2: every log line is tagged with the project name (`[webAxum...]`), so several RustRover windows can share one log.

### Phase 4 — Settings page, modes, launcher
| # | Task |
|---|---|
| 4.1 | Settings page with **all** VS Code settings that apply (list in PARITY.md §L) plus ours (integration mode, `claude` path) |
| 4.2 | Official plugin status shown; Built-in / Official / Auto modes working |
| 4.3 | Windows launcher: find `claude.exe` / `claude.cmd`, `claudeProcessWrapper`, `environmentVariables`, clear error when missing |
| 4.4 | Health-check notifications |

**Done when:** each setting persists across restarts, mode switching shows or hides our `/ide` entry, and a bad `claude` path gives a readable error.

Done in v0.9.0 (`settings/`, `cli/ClaudeCli.kt`):
- Settings > Tools > Claude UI, saved in `claude-ui.xml`. **Only settings that already do something**; each VS Code panel
  setting (PARITY §L) is added in the phase that gives it an effect, instead of shipping switches that do nothing.
- Integration mode Built-in / Official / Auto with the official plugin's status. Applying starts or stops the bridge in every
  open window right away (each bridge has its own disposable scope, so stopping it leaves no listeners or pending diffs behind).
- Claude CLI path (empty = PATH `claude.exe`/`claude.cmd`/`claude.bat`, then `~/.local/bin`) with a **Test** button (`claude --version`).
  Start Claude Session shows "Claude CLI not found" with an "Open settings" action instead of failing silently.
- `attachSelection`, `attachOpenFile`, `respectGitIgnore` (VS Code names), applied live by the selection tracker.
- Moved to Phase 5 (they only affect a process we launch): `environmentVariables`, `claudeProcessWrapper`.

### Phase 5 — Terminal mode (= VS Code `useTerminal`)
| # | Task |
|---|---|
| 5.1 | A Claude session inside RustRover running `claude`, already bound to our bridge |
| 5.2 | Several sessions (more tabs), toggle focus editor ↔ Claude |

**Done when:** Tools > New Claude Session opens a working, connected session inside RustRover. **This is the first daily-usable release.**

Done in v0.8.1: every RustRover terminal tab gets `CLAUDE_CODE_SSE_PORT` + `ENABLE_IDE_INTEGRATION` (terminal customizer, `order="last"`),
so `claude` there connects by itself. External and WSL terminals still need `/ide`.

Done in v0.10.0 (`ClaudeTerminal.kt`, `cli/SessionCommand.kt`):
- **Tools > New Claude Session** opens a "Claude" tab in RustRover's **Terminal tool window** (not a separate tool window: reusing
  the IDE terminal is simpler and is what VS Code's `useTerminal` does) and types the right command for the shell:
  `claude` when it is on PATH, otherwise the quoted path (`& "..."` in PowerShell). Each run opens another tab.
- **Alt+Shift+K** toggles focus editor ↔ newest Claude tab (opens a session if none). VS Code's Ctrl+Esc can't be used on Windows:
  the OS opens the Start menu.
- Missing CLI → notification with "Open settings". Health check now looks at live connections.
- v0.10.1: the first New Claude Session logged "This method is forbidden on EDT": asking the terminal for its shell may block
  the first time. Preparation (CLI lookup, bridge start, shell lookup) now runs on a background thread; only the tab opens on the EDT.
- The hidden PTY launch is gone. `environmentVariables` / `claudeProcessWrapper` move to Phase 6, where the plugin starts the
  process itself (in a shared terminal they would leak into every terminal tab).

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

v0.11.0: the spike is a developer action, not UI yet. It drives `claude -p --input-format stream-json --output-format stream-json
--verbose --include-partial-messages --permission-mode default --permission-prompt-tool stdio` from the project root, with our bridge's
env vars set, and logs every line to `~/.claude-meta/spike-<time>.log`: initialize, a Write to a temp folder (expects `can_use_tool`,
answered allow + `updatedInput`), `set_permission_mode`, a second turn, an interrupt, then `--resume` in a new process. It also logs
JCEF support and bridge connections. `--permission-mode default` is explicit because the user's default is auto mode, which never asks.
Research notes: allow replies must echo `updatedInput` on CLI 2.1; a reported bug has the CLI not sending `can_use_tool` at all.

**Done when:** a throwaway panel can send a prompt, render the streamed answer, approve one permission, and stop a turn.

v0.13.0 (6.6): `webview/` (TypeScript, esbuild, marked + DOMPurify) is built in CI (Node 24, `npm ci`, typecheck, build) and packaged
as `/webview/panel.js|css`. `panel/ChatPanel.kt` loads it in JCEF with every panel security rule: CSP with a fresh nonce per load,
no remote resources, navigation cancelled, a closed validated message set (`ready`, `ping`, `openLink`), DevTools menu off, IDE
theme colors as CSS variables. The "Claude Chat" tool window is declared in `claude-ui-jcef.xml`, so it only exists with JCEF.
Checked before pushing in Chromium under the same CSP: injected `<img onerror>`/`<script>` shown as text, `javascript:` link removed,
link click routed to Kotlin, no CSP violations, no network requests.

Spike v1 result (2026-10-30, CLI 2.1.312): **6.1 pass** (multi-turn in one process, every event type documented),
**6.2 pass** (`can_use_tool` arrives with permission suggestions; allow + `updatedInput` accepted, file written),
**6.4 pass** (`set_permission_mode`, `interrupt` work mid-session), `--resume` in a new process works.
**6.3 open**: the bridge env vars don't attach `-p` sessions (0 connections). **6.5 open**: `JBCefApp` not loadable
(IDE 2026.2 moved JCEF into a bundled plugin; v0.11.1 declares it as an optional dependency). Details: `STREAM-PROTOCOL.md`.

Spike v2 result (2026-10-30, RustRover 2026.2.4): **6.5 pass** (JCEF available with the optional dependency).
`--ide` doesn't attach `-p` sessions either, so **6.3 → plan B**: a loopback HTTP MCP server of ours passed with `--mcp-config`
(spike v3). The user's RustRover is **2026.2** while we compile against 2025.2: move the build target to 2026.2 before the panel work.
v0.12.0: build target moved to **RustRover 2026.2.4** (the user's IDE). That platform needs **Java 25** (CI: Temurin 25),
Kotlin **2.4.21** (reads the bundled 2.4.0 stdlib), IntelliJ Platform Gradle Plugin **2.19.0** (needs **Gradle 9**: wrapper 9.8.2),
and JCEF as `bundledPlugin("com.intellij.modules.jcef")`. The plugin now installs on 2026.2 (build 262) and later only. Tested 2026-10-29: build green, sessions, Alt+K and settings work.

v0.12.1 (spike v3, PLAN 6.3): `protocol/HttpMcpServer.kt` serves MCP over HTTP (JSON responses, no SSE) on loopback with a
bearer token, reusing `McpDispatcher` (which can now echo the client's protocol version). The spike passes it to `claude -p`
with `--mcp-config <temp file>` (a file, to avoid quoting the token on the Windows command line) and asks Claude to call
`mcp__claude-ui__getDiagnostics` once. Unit tests cover auth, notifications (202), GET (405), version echo and tool calls.

v0.12.2: plugin verifier flagged 3 internal API usages (`PluginManagerCore.getPlugin`, `PluginManager.findEnabledPlugin`), all in
version / official-plugin detection. Replaced with public `isPluginInstalled` / `isLoaded` / `isDisabled`, and the plugin's own
version now comes from `claude-ui.properties` written by Gradle. Rule added to CLAUDE.md.
If 6.1 or 6.2 fails, the panel waits, and terminal mode (Phase 5) remains the product.

### Phase 7 — Chat core (PARITY §B, §E)
Carry-over from the 6.6 test: links don't show the hand cursor in JCEF (the `<a>` already asks for it, so the cursor change
probably isn't forwarded by the embedded browser; check CefDisplayHandler.onCursorChange / rendering mode before touching CSS).

7a (v0.14.0): `panel/ChatSession.kt` runs one `claude -p` stream-json process per conversation (started on the first message) with
our HTTP MCP server (`getDiagnostics`); `protocol/StreamEvents.kt` turns CLI events into the panel's message set (unit-tested with
spike-shaped lines). The panel: prompt box (Enter / Shift+Enter), streamed sanitized Markdown, tool cards with result preview,
Stop button and Esc (interrupt), New session, cost and duration per turn. **Permission requests are denied** with a notice until
the Phase 8 cards. Cursor: a display handler mirrors JCEF cursor changes on the visible component. Simulated in Chromium under the
panel CSP before pushing (rendering, keys, injected HTML stays text, no network).
7a test (v0.14.0), 2026-10-30: conversation, Read card, denied permission, Esc and New session all work. Two bugs:
(1) every answer appeared twice: the CLI sends one `assistant` event per content block (one-element `content`, index 0),
while the stream index also counts thinking blocks, so `msg:1` (streamed) and `msg:0` (final) never matched. Fix (v0.14.1):
both messages carry `messageId` and the panel gives each final text to the oldest unfinished streamed block of that message.
(2) no hand cursor: the cursor was set only on the outer component; v0.14.1 also sets it on `cefBrowser.uiComponent` and logs
each cursor type JCEF reports once (`panel cursor type N`; 12 = hand), so the next log shows whether the handler runs at all.
v0.14.1 test: no more duplicates; still no hand, and the log has no `panel cursor type` line, so JCEF never calls
`onCursorChange` here. v0.14.2 drops that handler: the page posts `cursor` (`pointer`/`default`, the only accepted values)
when the mouse enters or leaves a link, button or `<summary>`, and Kotlin sets the Swing cursor.
v0.14.2 test: hand cursor works over links, buttons and Output.

### Rename to Clawed UI (v0.15.0, 2026-10-30)
Name **Clawed UI** ("clawed" sounds like "Claude"; claws for Ferris), id `io.github.mrglasses.clawedui`, `/ide` label
**RustRover (Clawed UI)**, settings under Tools > Clawed UI, panel MCP server `clawed-ui`. Kept on purpose: the settings file
`claude-ui.xml` (settings carry over), Kotlin package names, the `~/.claude-meta` log folder. Lock files of the old label are swept.
A new id is a new plugin for the IDE: uninstall "Claude UI for RustRover" before installing.
Marketplace search (checked 2026-10-30, plugins-ranking docs) matches id, name, description, tags and vendor, then weighs
downloads and rating, so "Claude" in the description and tags makes the plugin findable but a new plugin ranks low at first.
7b (v0.16.0): thinking blocks from `content_block_start`/`thinking_delta`/`content_block_stop` ("Thinking…" then
"Thought for N s"; Ctrl+O opens or closes all; if the CLI sends no reasoning text the block says so), the TodoWrite list
above the composer (replaces its tool card), messages typed while busy are queued (removable, sent after each turn; the button
reads Queue while you type, Stop otherwise), times on messages and turn footers, Copy per turn (Markdown source, through
Kotlin to the IDE clipboard; the page has no clipboard access). The log now names each tool Claude uses and the raw
`tool_name` of denied requests: v0.15.0 denied a request shown as `GetDiagnostics`, which is probably the display name of our
`mcp__clawed-ui__getDiagnostics`; the next log confirms it, and whether CLI 2.1.314 still uses TodoWrite.
7b test (v0.16.0), 2026-10-30: times, Copy and the denied-request log work; `GetDiagnostics` is the display name of our
`mcp__clawed-ui__getDiagnostics` (log: `tool_name=mcp__clawed-ui__getDiagnostics`). Three fixes in v0.16.1:
(1) Ctrl+O did nothing: the IDE keymap takes it before the page. Now a panel-local shortcut (registerCustomShortcutSet on
the browser component) sends `toggleThinking` to the page. (2) No to-do list: Claude Code leaves TodoWrite and the task
tools out by default on Opus 4.8+ / Sonnet 5+ (code.claude.com/docs/en/agent-sdk/todo-tracking). New setting
Tools > Clawed UI > Chat Panel > "Show a to-do list" (off by default) starts claude with `CLAUDE_CODE_ENABLE_TODO_TOOLS=1`;
the panel now also reads TaskCreate / TaskUpdate (id from `tool_use_result.task.id`) besides TodoWrite. (3) "One per line"
came out as one paragraph: Markdown now renders single newlines as line breaks (`breaks: true`).
v0.16.1 test: Ctrl+O and the to-do list work. Thinking blocks were always empty: without Claude Code's
`showThinkingSummaries` the CLI sends no reasoning text. v0.16.2 adds "Show Claude's reasoning" (on by default), which
passes `--settings <temp file>` with `{"showThinkingSummaries":true}` (a file, because claude.cmd runs through cmd.exe,
which mangles quotes in arguments). The to-do list gets a Hide button (back on the next update); like VS Code's, the list
belongs to Claude, so items can't be ticked by hand. Note: the CLI keeps tasks from earlier sessions of the project; the
panel shows only the ones created in the current conversation.
v0.16.2 test (2026-10-30): reasoning summaries and the to-do list work (log: thinking summaries on, TaskCreate x3,
TaskUpdate). 7b done.

7c (v0.17.0): Focus view (header button, `/focus`, Ctrl+Alt+F as a panel-local shortcut) hides tool cards and thinking;
a date line before the first message of each day; setting "Show message times and date lines" (VS Code
`showMessageTimestamps`, sent with `hello`); `/copy`, `/copy N` and `/clear` run in the panel and never reach Claude;
the `/` menu (arrows, Enter/Tab, Esc) lists the panel commands plus the CLI's `slash_commands` from `system/init`
(so CLI commands appear after the first message); LaTeX through KaTeX 0.19.0 with MathML output only (Chromium draws it;
no fonts, CSS or inline styles, so the CSP is unchanged; DOMPurify gets the MathML profile; the TeX annotation is removed
because the sanitizer would leave its text visible); sign-in card when the CLI isn't signed in (`error:
"authentication_failed"` on an assistant event, or an error result asking for /login), whose button opens a Claude
terminal tab for `/login`. Simulated in Chromium under the panel CSP (menu, /copy, Focus, math, injected `<script>` stays text).
v0.17.0 test (2026-10-30): Focus, `/` menu, `/copy 2`, LaTeX ok; sign-in skipped (needs a logged-out CLI). Turning off
times/dates had no effect: closing the tool window keeps the page, so `hello` never came again. v0.17.1 sends a `settings`
message on load and whenever the settings page is applied. After New session the `/` menu keeps the last session's CLI
commands, which is fine (same CLI, same commands).
Not yet (rest of Phase 7): `focusView` setting (Focus view does not survive a restart yet).
Sending and receiving, streaming Markdown, code blocks, tool-call rows, thinking blocks (Ctrl+O), to-do list, queued messages,
Shift+Enter / `useCtrlEnterToSend`, Stop / Esc, timestamps, scroll setting, Focus view (Ctrl+Alt+F), copy response, `/copy`, spinner verbs,
LaTeX math, files Claude sends, sign-in screen, the `/` command menu.

**Done when:** a normal coding conversation works fully in the panel, without a terminal.

### Phase 8 — Permissions and review (PARITY §D)
Permission mode indicator and switching, permission cards with "save to" destinations, diffs from the panel (reusing Phase 1's viewer with per-change review),
plan review as a Markdown document with inline comments, `/plan` variants, Claude's questions (multiple choice + Other), `initialPermissionMode`,
Bypass only when `allowDangerouslySkipPermissions`, `autosave`.

**Done when:** in each mode (Auto, Manual, Plan, Edit automatically), approving, rejecting and plan feedback work from the panel.

8a (v0.18.0): `protocol/Permissions.kt` (pure, unit-tested) turns each `can_use_tool` request into a card and builds the answer.
Tool cards: what will run (Bash/PowerShell command, Edit old/new lines, Write content, capped at 4000 chars), the CLI's
reason, **Yes**, one button per `permission_suggestions` entry ("Yes, and switch to Edit automatically", "Yes, and don't ask
again for Bash(npm test)" with a "save to" menu: this session / this project just you / this project shared / all projects),
**No**, and "No, and tell Claude what to do instead". Questions (AskUserQuestion): radio or checkboxes per question, "Other"
with your own text, Answer / Don't answer / reply in your own words (`answers` keyed by question text, checked against the
real questions in Kotlin). Plan (ExitPlanMode): the plan as sanitized Markdown, Approve / suggestions / No / Keep planning with
feedback. Every answer is checked in Kotlin against a pending request (rule 4) and answered exactly once; saving a rule to a
settings file and switching to Bypass need a native IDE dialog (rule 5). Mode menu in the header (Ask before edits, Edit
automatically, Plan mode, Auto mode, and Bypass only with "Allow Bypass permissions" on); it follows the CLI's mode
(`system/status`), and a mode chosen before the first message is used to start the session. New settings: Initial
permission mode, Allow Bypass permissions. `control_cancel_request` closes a card; a failed mode switch shows a notice.
8a test (2026-10-31): command card, deny with instructions, saved rule with native confirmation, questions, plan feedback and
Edit automatically all work. Read-only commands (`git status`) are approved by the CLI itself, so no card appears for them.
v0.18.1: clicking "Other…" now selects its radio/checkbox.

8b (v0.19.0): an Edit / MultiEdit / Write request opens in the IDE diff viewer as soon as its card appears (without taking the
focus; "Open diff" on the card reopens it). `protocol/EditProposal.kt` (pure, unit-tested) computes the proposed text from the
current editor text (\n line ends); if the old text isn't found, no diff is shown and the card still works. Accept in the diff =
allow; if you edited the right side, the answer carries your text: Write gets the new `content`, Edit and MultiEdit become one
replacement of the whole file on disk by your text, in the file's own line ends (CRLF stays CRLF). Reject = deny. Closing the
diff decides nothing. Answering on the card (or a CLI cancel) closes the diff. The plugin never writes the file: the CLI does.
Plans: "Open as document to comment" opens the plan as an editable Markdown file with a banner (Send my comments / Approve
plan); comments go back as a deny with the annotated plan (up to 120k chars). Setting "Save files before Claude reads or writes
them" (VS Code `autosave`, on): saves all editors before each message and the target file before showing an edit.
8b test (2026-10-31, v0.19.1; v0.19.0 didn't compile): diff Accept, Accept with your edits, Reject, answering on the card
closes the diff, plan comments from the document, autosave: all ok. Phase 8 done.

### Precheck (v0.19.2, 2026-10-31)
After the v0.19.0 compile error, every change is checked before it reaches you: `tools/precheck/precheck.sh` compiles with
kotlinc 2.4.21 against your RustRover 2026.2.4 jars, runs all unit tests and builds the plugin zip; the plugin verifier
1.411 then checks the zip against your full RustRover install (see `tools/precheck/README.md`). Its first run found two
"scheduled for removal" API calls, fixed in v0.19.2: `JBCefJSQuery.create(JBCefBrowser)` (now the `JBCefBrowserBase`
overload) and `SimpleListCellRenderer.create` in the settings page (now a `ColoredListCellRenderer`). Verifier result:
Compatible, 0 scheduled-for-removal, 8 deprecated (terminal API: `createShellWidget`, `LocalTerminalCustomizer`; and
`ToolWindowFactory` defaults), 6 experimental (`ToolWindowFactory` defaults).

### Phase 9 — Context and mentions (PARITY §C)
Selection and open-file chips with X, `@` fuzzy file and folder search, Alt+K, `@terminal:name` (JetBrains terminal buffers),
pasted images, Shift+drag files, attachments removable, ignore rules, `@browser`.

**Done when:** each kind of context reaches Claude, which you check by asking about it.

9a (v0.20.0): chips above the prompt box show what goes with the next message: the open file (with `attachOpenFile`, or
always together with a selection) and the selection (`name:5-10 (6 lines)`, or "text not shared" when `attachSelection` is
off or the file is excluded / git-ignored with `respectGitIgnore`). X drops a chip until the editor shows something else.
Kotlin reads the context again at send time and sends it as text blocks before the prompt, worded like Claude Code's IDE
integration (`<ide_opened_file>`, `<ide_selection>`; `protocol/PanelContext.kt`, unit-tested). `@` in the prompt opens a
fuzzy search over project files and folders (`FuzzyPaths`, unit-tested; list cached 30 s, excluded and git-ignored files left
out); picking a folder keeps browsing inside it. Alt+K with the chat panel showing inserts `@path#5-10` into its prompt box;
otherwise it goes to a terminal session as before. Precheck: 74 tests, verifier Compatible (no new deprecated APIs).
9a test (2026-10-31): selection, X on a chip, git-ignored file, `@` search, Alt+K: all ok.

9b (v0.21.0): `@` also lists RustRover's terminal tabs as `@terminal:<name>` (spaces written as "-"); at send time Kotlin
reads that tab's text (`TerminalWidget.getText()`, **experimental API**, wrapped so a change can't break the panel) and adds
its last 30k characters as a `<terminal_output>` block. Pasted images (PNG, JPEG, GIF, WebP; up to 5, 5 MB each) show as
chips with a thumbnail and go as `image` blocks; Kotlin checks type, base64 and size again (rule 4). Files dropped on the
panel (from the project view or Explorer; Shift not needed) become chips and are sent as `@path` mentions after the prompt;
the drop is caught by the IDE's DnD support on the Swing component, since the page can't receive IDE drags. Chips and
attachments are taken when you press Enter, so a queued message keeps its own. Precheck caught two compile errors before
hand-over (`DnDEvent.transferable`, `TerminalWidget.text`). 76 tests; verifier Compatible (+1 experimental: getText).
9b test (2026-10-31): images, dropped files and a queued message with an image work. `@terminal` sent no output: the tab was
found but nothing was read, and the panel dropped the mention silently. v0.21.1 reads the reworked terminal (2026.2's default)
through its output editor (the tab's data context gives `CommonDataKeys.EDITOR`; public API), keeps `getText()` as the
fallback for classic tabs, logs which way worked and how many characters (never the text), and shows a notice when a tab
can't be read.
v0.21.1 test: the notice appeared and the log said "could not read the tab": the data context gave no editor. v0.21.2 looks
for the terminal's editor among all IDE editors (`EditorFactory.allEditors`) whose component is inside the tab, and when
nothing is found logs what the tab is made of (component class, editors inside, widget class) for the next step.
The JCEF "JBCefApp$Holder <clinit> requests ProxyMigrationService" error at install is the platform's own (logged when the
chat panel is the first JCEF user after a plugin reload); harmless, nothing public to avoid it, so it's left alone.

### Phase 10 — Sessions (PARITY §F)
History (search, by time), resume, titles, rename, archive and auto-archive, groups, filters, several conversations in tabs and windows,
status dots, reopen closed (Ctrl+Shift+T inside the panel), continue after reload, "still open somewhere else" and "not reopened" guards,
`/export`, Web tab (cloud and Remote Control sessions).

**Done when:** sessions survive an IDE restart, and history, groups and filters behave like VS Code's.

9b closed (2026-10-31): v0.21.2 reads the reworked terminal through its editor (log: "read 961 chars (reworked terminal)").

10a (v0.22.0): the panel reads Claude Code's own session files (`~/.claude/projects/<project dir>/<id>.jsonl`, read-only;
`protocol/SessionFiles.kt`, unit-tested). History (header button or `/resume`): this project's conversations, newest first,
with title (your rename, else Claude's, else the first prompt), age and message count; the search box matches titles and
the whole conversation text. Opening one replays it in the panel (your prompts, Claude's text, tool cards) and the next
message starts `claude --resume <id>`. Continue after reload (setting, VS Code `continueAfterReload`): when the panel opens
and the last conversation here was active in the last 10 minutes, it comes back the same way. `/export` saves the
conversation as Markdown (native save dialog); `/export copy` copies it. Session ids are checked against the project's
files before use (rule 4). Precheck caught a `/*` inside a KDoc comment (unclosed comment) before hand-over.
10a test (2026-10-31): history, search, resume and `/export` work; continue after reload works (log: "resumed session after
reload"). Two fixes in v0.22.1: the "Resumed …" note was at the top of a long replay, out of sight, so it now comes at the end
together with "Your next message continues this conversation"; and a reply cut off by closing the IDE isn't in the session
file (the CLI saves finished blocks only), so the replay now ends with "Claude's reply to the last message didn't finish …"
when the conversation stops after your prompt or mid-tool.
v0.22.1 test (2026-11-01): note at the end, interrupted reply flagged, "continue" resumes it. 10a done.

10b (v0.23.0): the "Claude Chat" tool window holds several conversations, one tab per conversation, each with its own `claude`
process (`panel/ChatTabs.kt`, a project service). "+" in the tool window title opens a new tab; a tab is named after its
first prompt (or the resumed conversation's title); closing the last tab opens a fresh one. Tab dots like VS Code: blue while
a permission waits for you, orange when a turn ends in a tab you aren't looking at (cleared when you open it). Ctrl+Shift+T
inside the panel reopens the last closed conversation (up to 20 remembered). Opening a conversation that is already open in
another tab switches to that tab instead of opening it twice. Only the first tab continues the last conversation after a
restart. Alt+K goes to the selected tab.
v0.23.0 test (2026-11-01): new tab, naming and switching to an open conversation work. Tabs had no close button: the tool
window needs `canCloseContents="true"` in its declaration. The dots never showed: tool window tabs hide content icons unless
the content has `ToolWindow.SHOW_CONTENT_ICON`. Both fixed in v0.23.1.
v0.23.1 test: dots, close and Ctrl+Shift+T work. Found: with 3 tabs and long names one tab (usually the first) could not be
reached. v0.23.2: tab names are cut at 18 characters (the full title shows on hover), and a list button next to "+" opens
"Open Conversations", every open tab with its full title and state, to switch to any of them.
v0.23.3 (before testing 0.23.2, at your request): the tabs are now an editor-style tab bar (`JBTabsFactory.createEditorTabs`)
inside one tool-window content, like the official plugin: one row that scrolls with the mouse wheel, a list arrow for tabs
that don't fit, and a close button on each tab. Names up to 24 characters (full title in the tooltip and in "Open
Conversations"). The tool window's own content tabs (and `canCloseContents`) are no longer used.
v0.23.3 test (2026-11-01): scrolling tab bar, hidden-tab list, close and reopen, dots: all ok. 10b done.

10c (v0.24.0): History gets filters (Active, Open in a tab, Archived, All) and a "⋯" menu per conversation: Rename, Archive /
Unarchive, Move to a group, Remove from group. "+ Group" creates a group; each group heading has its own "⋯" (rename, delete:
deleting keeps the conversations). "Archive all shown" / "Unarchive all". Setting "Archive conversations idle for" (VS Code
`archiveInactiveSessions`: never, 1, 2, 7, 14 days); unarchiving one keeps it out of auto-archive. Renames show in the tab
too. All of this is the plugin's own notes (`SessionNotes`, project workspace file; `SessionOrganizer`, unit-tested): Claude
Code's session files are never written. Verifier caught `BaseState.intIncrementModificationCount()` (internal API) before
hand-over; changes now assign new collections instead.
v0.24.0 test: auto-archive works. The tab close buttons looked gone (JBTabs shows tab actions only on hover by default) and
the History "⋯" wasn't found. v0.24.1: close buttons always shown (`setTabLabelActionsAutoHide(false)`); right-click on a
conversation tab gives Rename (native input), Archive, Close; the History menu button is a bordered "•••", and right-clicking
a History row opens the same menu.
v0.24.1 test: History menu, groups, archive, filters and tab rename all work; the tab "×" still didn't show. v0.24.2 sets the
tab's actions after `addTab` (so the label builds its buttons), gives the close action an explicit always-visible update and
the IDE's close/hover icons, and adds Ctrl+F4 inside the panel to close the current conversation tab.
v0.24.2 test (2026-11-01): tab "×", Ctrl+F4 and everything else ok. 10c done.

### Phase 11 — Checkpoints, agents, background work (PARITY §G, §H)
Fork from here, rewind code, fork + rewind; agent map (tree, status, time, tokens, transcript, stop), background tasks with live output,
Run in background, `/tasks`.

**Done when:** rewinding restores files, and a background subagent can be watched and stopped from the map.

11a (v0.25.0), checkpoints. Protocol read from the CLI's own schema (claude 2.1.316):
- The panel gives every prompt its own UUID (`"uuid"` on the stream-json user message; the CLI keeps it as the session
  entry's uuid) and the CLI runs with `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true`, so every prompt is a restore point.
- Hovering a prompt shows ↶ with: Fork conversation from here, Rewind code to here, Fork conversation and rewind code.
  Prompts of resumed conversations get it too (their uuid comes from the session file).
- Rewind: control request `rewind_files` with `dry_run` first; a native dialog lists the files (+/− lines) and says that
  Bash and subagent changes aren't undone; then the real rewind, and the IDE refreshes those folders. The conversation stays.
- Fork: a new tab shows the conversation up to that prompt and puts the prompt in the input box to edit; its process starts
  on send with `--resume <id> --fork-session --resume-session-at <entry before the prompt>`. The original stays as it was.
  Forking at the first prompt is a new conversation with that prompt in the box.
- Refused (with a notice) while Claude is still answering, and for messages not in this tab's own session file.
v0.25.0 test (2026-11-01): rewind, nothing-to-rewind, fork, fork + rewind, mid-turn refusal, resumed conversations: all ok.

11b (v0.26.0), agent map and background work (stream events and control requests from the CLI's schema, `AgentTasks`):
- "Agents and tasks" box above the composer, opened by the first task: a tree (`parent_task_id`) of subagents, background
  commands and monitors with status, elapsed time, tokens, tool count and the tool in use (`task_started`, `task_progress`,
  `task_updated`, `task_notification`, `background_tasks_changed`).
- Subagents: "Transcript" shows the task given to the agent, its replies and tool calls (stream events with
  `parent_tool_use_id`, no longer dropped) and its result. Commands and monitors: "Output" shows the last 8 KiB, refreshed
  every 2 s while running (`get_task_output`, escape sequences removed). "Stop" on anything running (`stop_task`).
- "Run in background" on a running Bash/Agent tool card (`background_tasks` with its `tool_use_id`, Ctrl+B for one call).
- `/tasks` shows the map again after "Hide". Kotlin accepts actions only for tasks and tool calls this session reported.
v0.26.0 test (2026-11-01): map, transcript, Stop, background output, Run in background (Bash with `ping`, and a subagent),
`/tasks`, New session: all ok. (`sleep 60` can't test the button: the CLI itself refuses long foreground sleeps.) 11 done.

### Phase 12 — Model, usage, status (PARITY §B, §I)
Model picker with Effort and Ultracode, extended thinking toggle, context indicator, prompt-cache clock, `/usage` dialog (bars, recommendations + Fix, attribution),
`/status`, sign out, Report a problem.

**Done when:** model and effort change mid-session, and the usage dialog matches the CLI's numbers.

12a (v0.27.0), a bar under the prompt box (`ModelControls`, control requests from the CLI's schema):
- Model button ("Opus 5.5 · High ▾", also `/model`): models from `list_models` (disabled ones greyed), Effort levels the model
  supports, Extended thinking, and Ultracode when the CLI says it's available (`get_settings` → `applied`). Changes go to the
  running session: `set_model`, `apply_flag_settings {effortLevel | ultracode}` (session only, like `/effort`),
  `set_max_thinking_tokens` (0 = off). Opening the menu starts the process if none is running. Refused mid-turn.
- "Context 23%" after every turn (`get_context_usage`, summary), red from 80%; click sends `/compact`.
- Prompt cache clock: "Cache 4 min" counting down from each turn's end, 60 min when the turn wrote to the 1-hour cache, 5 when
  only to the 5-minute one (`result.usage.cache_creation`); red "Cache expired" after.
v0.27.0 test (2026-11-01): all ok except that no tooltip appeared (the context meter's). JCEF in the IDE never shows the
native `title` tooltip, so no tooltip in the panel ever worked. v0.27.1 draws its own (`tooltip.ts`, text only) for every
element with a `title`.

12b (v0.28.0), the header's "•••" menu and panel commands (`UsageReport`):
- `/usage`: a card with the plan's limit bars (5 hours, week, per model, extra usage) and when they reset, this session's cost,
  API time and lines, and "What's using your limits" for the last 24 hours / 7 days (behaviors, recommendations, most-used
  agents, skills, plugins, MCP servers). "Fix" sends the CLI's own suggested messages (`fix.messages`, e.g. `/effort high`)
  as your next messages; Kotlin sends only those. Data: `get_usage` (experimental in the CLI; every field optional here).
- `/status`: Clawed UI, IDE and Claude Code versions, folder, account (from the `initialize` answer), model and effort,
  MCP servers with their state (`get_binary_version`, `get_settings`, `mcp_status`).
- `/logout` (Sign out): native confirmation, then `claude auth logout`; the session stops.
- `/bug` (`/feedback`): Claude Code's `/bug` is interactive, so it opens a Claude terminal tab to type it there.
  `/bug plugin`: a new GitHub issue for Clawed UI with the versions filled in (no conversation text).
  (`submit_feedback` exists in the CLI but is marked internal, so it isn't used.)
v0.28.0 test (2026-11-01): tooltips, `/usage` (numbers match the CLI), Fix, `/status`, `/bug`, `/bug plugin`: ok. Sign out
skipped on purpose (you didn't want to sign out). 12 done.

### Phase 13 — Customize dialogs (PARITY §I)
Slash commands browser and `/skills` visibility, output styles (+ build custom), hooks editor, permissions editor, memory, instructions (CLAUDE.md),
sandbox, Chrome, `/mcp` dialog, `/plugins` dialog (scopes, config form, marketplaces), Remote Control toggle.

**Done when:** each dialog's changes show up identically in a terminal `claude` session (they share the same config files).

How: like the VS Code extension, the plugin never writes Claude Code's settings itself. It reads through control requests
(`get_skills_dialog`, `get_settings`, ...) and writes through the CLI's own writers: `update_settings` for what it allows,
and the hidden `claude edit-<thing> --json` subcommands (skill overrides, permission rules, memory settings, sandbox, Chrome)
that the CLI says are for the VS Code extension. Those can change with the CLI, so each one is wrapped in a small, tested
protocol object and a failure is shown, not hidden.

13a (v0.29.0), a Customize view (••• → Customize, `/customize`; it replaces the transcript like History; `Customize`):
- Commands: every command the CLI lists (`initialize` → `commands`, updated by `commands_changed`) with its argument hint,
  description, aliases and built-in / custom; filter; click puts `/name ` in the message box. The `/` menu now shows the
  same descriptions.
- Skills (`/skills`): each skill with source and token estimate, and its state (On, Name only, Only when you type it, Off);
  locked ones say why. Changes go through `claude edit-skill-overrides --json` (saved in .claude/settings.local.json),
  then `reload_skills`.
- Output style (`/output-style`): the styles the CLI lists (`reload_output_styles` first, so new style files show up);
  picking one uses `update_settings` (localSettings `outputStyle`). "Build a custom style…" starts a message asking
  Claude to write the style file in .claude/output-styles/.
- Instructions: the four CLAUDE.md files Claude Code reads (project, project/.claude, CLAUDE.local.md, ~/.claude); Open,
  or Create… after a native confirmation (only those four paths, chosen by index in Kotlin).
v0.29.0 test (2026-11-02): all ok.

13b (v0.30.0), three more Customize tabs (`CustomizeEdits`):
- Hooks (`/hooks`): every hook by event and matcher with its source (`get_hooks_listing`); "+ Add a hook" and Edit
  (command hooks: event, matcher when the event takes one, command, timeout, which settings file) and Remove (any hook from
  a settings file). Plugin, managed and session hooks are read-only; a managed lock disables editing. Writes:
  `claude edit-hook --json` (target = the CLI's own stored entry). Every change shows the command in a native
  confirmation first (rule 5: a hook runs commands on your computer).
- Permissions (`/permissions`): Allow / Ask / Deny rules with the CLI's plain-language reading and source, extra folders
  (`list_permission_rules`). Add rules (one per line, a behavior, a settings file) and remove saved ones:
  `claude edit-permission-rules --json`. New Allow rules are confirmed natively (rule 5); the CLI's warnings are shown.
- Memory (`/memory`): auto-memory and auto-dream switches (`claude edit-memory-settings --json`), the instruction files
  (open; the user and project ones can be created), the memory folders (shown in the file manager) and the saved memories
  (open, or delete after a native confirmation) (`get_memory_dialog`). Paths are taken by index from the CLI's listing.
v0.30.0 test (2026-11-02): hooks, memory ok. Two problems. (1) A new permission rule showed only after leaving and
reopening the tab: the running session reads the settings file when its file watcher fires, so the listing right after the
edit was still the old one (the log also shows two `edit-hook` failures, most likely edits aimed at a stale row). v0.30.1
reads the tab again right away and at 1.2 s and 3.5 s. (2) `Bash(cargo check:*)` didn't stop the permission card: on
Windows Claude ran the command with its PowerShell tool (log: "permission request for 'PowerShell'"). Not a plugin bug;
v0.30.1 says so under the form on Windows and suggests `PowerShell(cargo test:*)` in the placeholder.
v0.30.1 test (2026-11-02): ok. 13b done.

13c (v0.31.0), MCP and Plugins tabs (`Extensions`). Format of `claude plugin ... --json` checked by running the CLI on
a throwaway config folder (CLAUDE_CONFIG_DIR) with a local test marketplace:
- MCP (`/mcp`): every server with status (●/✗/!/…/○), scope, transport and command or URL, tool count and error
  (`mcp_status`). Enable / Disable (`mcp_toggle`) and Reconnect (`mcp_reconnect`) on the running session; "Sign in…"
  for a server that needs OAuth opens a Claude terminal tab (`/mcp` there: the sign-in is interactive). Add (stdio command
  + arguments + KEY=value environment, or http / sse URL + headers; local, project or user) and Remove go through the
  public `claude mcp add` / `claude mcp remove`, each after a native confirmation that shows the command or URL. New
  servers load in new sessions. The panel's own `clawed-ui` server is listed but not editable.
- Plugins (`/plugins`): installed plugins (scope, version, on / off) with Options… (the plugin's `userConfig` as a form;
  secret values are never sent to the page, an empty secret keeps the stored one), Enable / Disable, Uninstall; available
  plugins from the marketplaces with a scope and Install; marketplaces with Update, Remove, and Add (owner/repo, URL or
  folder). All through the public `claude plugin ...` commands; install, uninstall and marketplace add / remove are
  confirmed natively; then `reload_plugins` applies the change to the running session.
- On Windows `claude.cmd` runs through cmd.exe, which breaks quotes and expands `%`, `^`, `!`: free text with those
  characters is refused there with a message to use a terminal (`claude.exe` has no such limit).
v0.31.0 test (2026-11-02): MCP list, add (stdio), disable / enable, remove; plugins install, disable / enable, uninstall,
marketplace add: all ok. 13c done.

13d (v0.32.0), Sandbox and Chrome tabs (`SandboxChrome`):
- Sandbox (`/sandbox`): whether it runs here (or why not), mode (sandboxed without asking / sandboxed with prompts / none, only
  the choices the CLI offers), the unsandboxed-fallback switch, commands excluded from the sandbox (+ add one), dependency
  errors and warnings, restrictions from the settings (`get_sandbox_dialog`). Writes: `claude edit-sandbox-settings --json`.
  Anything that loosens it (no sandbox, run without asking, fallback on, excluding a command) is confirmed natively.
- Chrome (`/chrome`): extension installed / connected (and to which browser), "Enabled by default"
  (`claude edit-chrome-settings --json`), and the CLI's own install / reconnect / site-permissions pages, opened by Kotlin
  in the system browser (only http(s) URLs from the CLI's answer).
- Not here: "Enable Remote Control for all sessions" (`remoteControlAtStartup` is a user setting that no CLI command
  writes; it goes with the Web tab in Phase 15) and plugin install links (a JetBrains URL handler, Phase 14).
v0.32.0 test (2026-11-02): ok. 13 done.

### Phase 14 — IDE polish (PARITY §A, §K, §L, §M)
Tool window / editor toolbar / status bar entry points, panel placement (`preferredLocation`), editor-split behavior, keymap (resolve the JetBrains conflicts listed in PARITY §K),
`/btw` side panel, bookmarks, onboarding page, URI handler (open with prompt/session, install-plugin links), screen reader support, third-party provider mode, Show Logs.

**Done when:** every row in PARITY.md is ☑ or explicitly marked Skip.

14a (v0.33.0), entry points (`PanelActions`):
- Tools > Claude (also in Find Action): Open Chat, New Conversation, Reopen Closed Conversation, Ask Claude, Show Logs
  (bridge.log in the editor). No global shortcuts are taken.
- "Ask Claude" (✻ icon, an original asterisk, not Anthropic's logo) in the editor's top-right toolbar
  (`EditorContextBarMenu`) and in the selection's floating toolbar (`Floating.CodeToolbar`): opens the chat and puts
  `@file#lines` (or `@file`) in the message box.
- Status bar item "✻ Claude", "✻ Claude · working" while a conversation answers, "✻ Claude · needs you" while a permission
  card waits; click opens the chat.
- Settings > Chat panel location (right / left / bottom), VS Code's `preferredLocation`: moves the panel when applied.
- Ctrl+N inside the panel: new conversation tab (panel-local, so "Go to Class" keeps working in the editor).
- The Phase 6 developer action "Run Stream Spike" is gone from the Tools menu.

---

## Part 3 — Beyond parity and release

### Phase 15 — Rust extras (the differentiator)
"Ask Claude to fix" on compiler errors, `cargo check` / `clippy` / `test` failures to the session, crate and workspace context, optional Rust toolchain environment (the `usePythonEnvironment` analogue).

### After all features — usage ring (your request, 2026-11-01)
Like the Claude desktop app: a small circle in the panel that fills with the plan's usage (5-hour window), and on click or
hover a quick box with the bars (5 hours, week, per model, reset times) and a link to the full `/usage` card. Data from
`get_usage` with `skip_behaviors` (no transcript scan), refreshed after turns.

### Phase 16 — Hardening and release
Several windows at once, clean shutdown and stale lock sweep, CLI version check with a "protocol mismatch" message, security review (loopback only, no token in logs, lock file permissions on Windows),
performance on long conversations, README, license, screenshots, changelog, Marketplace signing, verifier and publishing.

---

## Decisions needed from you

| When | Decision |
|---|---|
| Before Phase 16 | Final plugin **name and id** (permanent once published). 2026-10-30: **Clawed UI**, id `io.github.mrglasses.clawedui` (v0.15.0) |
| Decided 2026-10-30 | **Full scope**: no publishing deadline, build the complete plan well. Positioning: no Node, only your official `claude` CLI; Rust focus as the differentiator |
| Phase 6 | After the spikes: build the chat panel, or stay terminal-only for now |
| Phase 6.5 | **UI stack: decided 2026-10-29, JCEF web view** with the panel security rules in `CLAUDE.md` |
| Phase 14 | Keymap choices where VS Code keys clash with JetBrains (Ctrl+N, Ctrl+Shift+T, Ctrl+Alt+F, Ctrl+Shift+Esc) |
| Phase 16 | Publish on the Marketplace or keep private. **License: decided 2026-10-29, Apache-2.0** (+ `DISCLAIMER.md`) |
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
