# VS Code extension parity checklist

Source: the official "Use Claude Code in VS Code" page (code.claude.com/docs/en/vs-code), read on 2026-10-27.
Every feature listed there, mapped to a phase in PLAN.md and a JetBrains equivalent.

Your CLI is 2.1.311. The highest minimum version that page mentions is 2.1.305, so **every feature below is available on your CLI**.

**How it's built** (column "Via"):
- **Bridge**: the IDE WebSocket/MCP bridge we already have (works today).
- **Stream**: the CLI's stream-json protocol (`claude -p --input-format stream-json --output-format stream-json`): the chat panel sends prompts and control messages and renders the events.
- **CLI**: the CLI already does the work, and the panel only offers a dialog or button for it.
- **UI**: pure IDE-side work (rendering, settings, keymaps).
- **Adapt / Skip**: VS Code-specific, so it gets a JetBrains equivalent, or nothing.

Status: ☐ not started · ◐ partial · ☑ done

## A. Entry points and layout — Phase 14

| Feature (VS Code) | JetBrains equivalent | Via | Status |
|---|---|---|---|
| Spark icon in Editor Toolbar (when a file is open) | Floating editor toolbar action | UI | ☐ |
| Activity Bar icon → sessions list | Tool window stripe icon → sessions list | UI | ☐ |
| Status Bar "✻ Claude Code" | Status bar widget | UI | ☐ |
| Command Palette "Claude Code: …" commands | Find Action (Ctrl+Shift+A) + Tools menu | UI | ◐ (Start Session only) |
| Panel in secondary sidebar / primary sidebar / editor tab, draggable | Tool window (right/left/bottom, draggable) or editor tab | UI | ☐ |
| `preferredLocation` setting | Same setting | UI | ☐ |
| `lockEditorGroups` | Adapt: open files outside the Claude editor split | Adapt | ☐ |
| Terminal mode (`useTerminal`) and "Open in Terminal" | Terminal tool window running `claude` | UI | ☐ (Phase 5) |
| Sign-in screen, "Not logged in" handling | Detect from stream, show sign-in button that runs `/login` | Stream/CLI | ☐ |
| Onboarding checklist, "Open Walkthrough", `hideOnboarding` | Getting-started page in the tool window | UI | ☐ |

## B. Prompt box — Phases 7, 9, 12

| Feature | Via | Phase | Status |
|---|---|---|---|
| Multi-line input (Shift+Enter), `useCtrlEnterToSend` | UI | 7 | ☐ |
| Queued messages while Claude works ("Message queued") | Stream | 7 | ☐ |
| Paste text stays visible; invisible-character notices | UI/CLI | 7 | ☐ |
| `/` command menu with inline suggestions | Stream/CLI | 7 | ☐ |
| Permission mode indicator (Auto / Manual / Plan / Edit automatically / Bypass) | Stream | 8 | ☐ |
| Model name button and picker, Effort row, Ultracode switch, `/model` | Stream | 12 | ☐ |
| Extended thinking toggle | Stream | 12 | ☐ |
| Context indicator and `/compact` | Stream | 12 | ☐ |
| Prompt cache clock (minutes left, red when expired) | Stream | 12 | ☐ |
| Agent count and agent map | Stream | 11 | ☐ |
| Side questions `/btw` in a panel beside the chat (newest 20 exchanges kept) | Stream | 14 | ☐ |

## C. Context and mentions — Phase 9

| Feature | Via | Status |
|---|---|---|
| Automatic selected text (footer shows line count, X to drop one selection) | Bridge (terminal) / UI (panel) | ☑ terminal mode (v0.7.0, tested); panel: Phase 9 |
| Automatic open file (name shown in prompt box) | Bridge / UI | ☑ terminal mode (v0.7.0) |
| `attachSelection`, `attachOpenFile` settings | UI | ☐ |
| Alt+K: insert `@file#5-10` mention | UI + Bridge (`at_mentioned`) | ☑ v0.7.0 (tested) |
| `@` fuzzy file and folder mentions (`@auth`, `@src/components/`) | UI (project index) | ☐ |
| `@terminal:name`: terminal output as context | UI (read JetBrains terminal buffer) | ☐ |
| Paste images from the clipboard | Stream (image content) | ☐ |
| Shift+drag files into the prompt box, X to remove attachment | UI | ☐ |
| Withhold selections from excluded or git-ignored files (`respectGitIgnore`) | UI | ◐ always on in v0.7.0; the switch comes with the settings page |
| `@browser` (Claude in Chrome) | CLI | ☐ |
| PDF page ranges | CLI (nothing to do) | ☑ |

## D. Permissions and review — Phase 8

| Feature | Via | Status |
|---|---|---|
| Permission prompt cards (allow / deny / tell Claude what to do instead) | Stream (control requests) | ☐ |
| "Save to" destination on rule-saving options (this session / project / all projects), arrow keys to change | Stream | ☐ |
| Side-by-side diff for edits in Manual mode | Bridge `openDiff` (terminal) / Stream (panel) | ☑ terminal mode (v0.6.2, tested); panel: Phase 8 |
| Editing the proposed side: Claude is told you changed it | Bridge / Stream | ☑ terminal mode (v0.6.2, tested: the CLI writes your edited text); panel: Phase 8 |
| Per-change Accept/Reject buttons in the diff (not shown above 100 changes) | UI | ◐ per-change reject via the diff viewer's own arrows (v0.6.0); dedicated buttons later |
| Accept / Reject Change at Cursor (context menu + commands) | UI | ☐ |
| Plan mode: plan opens as a Markdown document with inline comments for feedback | Stream + UI | ☐ |
| `/plan`, `/plan <task>`, `/plan open` | Stream/CLI | ☐ |
| Questions from Claude (multiple choice + "Other" free text) | Stream | ☐ |
| `initialPermissionMode` (user-level only), `allowDangerouslySkipPermissions` | UI + Stream | ☐ |
| `autosave`: save files before Claude reads or writes them | UI | ☐ |

## E. Conversation rendering — Phase 7

| Feature | Via | Status |
|---|---|---|
| Streaming Markdown responses, code blocks | Stream + UI | ☐ |
| Tool-call rows with results; live subagent progress rows | Stream + UI | ☐ |
| Thinking blocks collapsed, Ctrl+O expands or collapses all | Stream + UI | ☐ |
| Latest to-do list stays visible | Stream + UI | ☐ |
| Focus view (hide tool calls and thinking), Ctrl+Alt+F, `focusView` | UI | ☐ |
| Message timestamps and date lines, `showMessageTimestamps` | UI | ☐ |
| `scrollToBottomOnSend` | UI | ☐ |
| `spinnerVerbs` | UI | ☐ |
| LaTeX math (`$…$`, `$$…$$`, `\(…\)`, `\[…\]`, ```` ```math ````), copy gives TeX source | UI (KaTeX in the web view) | ☐ |
| Copy response (hover button), `/copy`, `/copy 2` | UI | ☐ |
| Bookmarks (bookmark response, Bookmarks panel, `/bookmarks`) | UI/CLI | ☐ |
| Files Claude sends ("Sent report.md …", click to open; network paths not linked) | Stream + UI | ☐ |
| Stop button / Esc ends the turn (background agents keep running) | Stream | ☐ |

## F. Sessions — Phase 10

| Feature | Via | Status |
|---|---|---|
| Session history: search by keyword, browse by time | CLI session files | ☐ |
| Resume a conversation (switch to its tab if already open) | Stream (`--resume`) | ☐ |
| AI-generated session titles | CLI | ☐ |
| Rename, archive, unarchive (one or all) | UI/CLI | ☐ |
| Auto-archive after N days, `archiveInactiveSessions` (1/2/7/14/0) | UI | ☐ |
| Session groups (create, move, multi-select, rename, delete; per workspace) | UI | ☐ |
| Filters: Active toggle; Needs input / Working / Completed / Open / Closed | UI | ☐ |
| Multiple conversations: Open in New Tab / New Window | UI | ☐ |
| Status dot on tabs (blue = permission pending, orange = finished while hidden) | UI | ☐ |
| Reopen Closed Session (Ctrl+Shift+T) | UI | ☐ |
| Continue after reload (editor tabs always; sidebar if active < 10 min), `continueAfterReload` | UI + Stream | ☐ |
| "Not reopened" guard after two failed opens | UI | ☐ |
| "Still open somewhere else" guard with "Open here anyway" | UI | ☐ |
| Mark Session as Unread, Add Session Tab to Group, Rename Session Tab | UI | ☐ |
| `/export` (copy or save to file) | CLI | ☐ |
| Web tab: resume cloud sessions and Remote Control sessions from claude.ai | CLI | ☐ |

## G. Checkpoints — Phase 11

| Feature | Via | Status |
|---|---|---|
| Hover a message → Fork conversation from here | Stream/CLI | ☐ |
| Rewind code to here | Stream/CLI | ☐ |
| Fork conversation and rewind code | Stream/CLI | ☐ |

## H. Agents and background work — Phase 11

| Feature | Via | Status |
|---|---|---|
| Agent map (tree, status, elapsed time, tokens, prompt, tool calls, transcript, stop) | Stream | ☐ |
| Background tasks in the map (shells, monitors, latest output) | Stream | ☐ |
| "Run in background" under a long command or subagent | Stream | ☐ |
| `/tasks` | Stream/CLI | ☐ |

## I. Customize dialogs — Phase 13

| Feature | Via | Status |
|---|---|---|
| Slash commands browser with filter; `/skills` with visibility toggles | CLI | ☐ |
| Output styles picker; "Build a custom style" | CLI | ☐ |
| Hooks viewer/editor (user, project, local; others read-only) | CLI | ☐ |
| Permissions rules viewer/editor (Allow / Ask / Deny) | CLI | ☐ |
| Memory: auto memory on/off, browse, read, edit, delete, reveal folder | CLI | ☐ |
| Instructions: open or create CLAUDE.md files | UI/CLI | ☐ |
| `/status` (version, account, model, MCP) | CLI | ☐ |
| `/sandbox` (mode, excluded commands) | CLI | ☐ |
| `/chrome` (Claude in Chrome connection) | CLI | ☐ |
| `/mcp` dialog: add, remove, enable/disable, reconnect, OAuth | CLI | ☐ |
| `/plugins` dialog: installed toggles, scopes, config form, uninstall, marketplaces tab | CLI | ☐ |
| Plugin install links (`install-plugin?plugin=…&marketplace=…`) | Adapt (JetBrains URL handler) | ☐ |
| Enable Remote Control for all sessions (`remoteControlAtStartup`) | CLI | ☐ |
| Sign out (`/logout`), Report a problem (`/bug`, `/feedback`) | CLI | ☐ |
| `/usage` dialog (plan bars, recommendations + Fix button, attribution, Day/Week) | CLI | ☐ |

## J. IDE bridge (terminal sessions) — Phases 1–3

| Tool / notification | Status |
|---|---|
| `openDiff` (blocking), `close_tab`, `closeAllDiffTabs` | ☑ v0.6.2 (tested) |
| `openFile`, `getOpenEditors`, `getWorkspaceFolders`, `getCurrentSelection`, `getLatestSelection`, `checkDocumentDirty`, `saveDocument` | ◐ (all implemented by v0.7.0; `openFile` selection pending test) |
| `selection_changed`, `at_mentioned` | ☑ v0.7.0 (tested) |
| `getDiagnostics` (model-visible) | ◐ v0.8.0 (pending test; open files only) |
| `executeCode` (Jupyter) | Skip: RustRover has no Python notebooks |

## K. Shortcuts (JetBrains defaults to check for keymap conflicts)

| Command | VS Code key (Win/Linux) | Plan | Status |
|---|---|---|---|
| Focus Input (toggle editor ↔ Claude) | Ctrl+Esc | Same, if free | ☐ |
| Open in New Tab | Ctrl+Shift+Esc | Pick another key: Windows reserves Ctrl+Shift+Esc for Task Manager | ☐ |
| New Conversation (`enableNewConversationShortcut`) | Ctrl+N | Only when Claude has focus: Ctrl+N is "Go to Class" in JetBrains | ☐ |
| Reopen Closed Session (`enableReopenClosedSessionShortcut`) | Ctrl+Shift+T | Only when Claude has focus: Ctrl+Shift+T is "Go to Test" in JetBrains | ☐ |
| Insert @-Mention | Alt+K | Same, if free | ☐ |
| Toggle Focus view | Ctrl+Alt+F | Check: Ctrl+Alt+F is "Introduce Field" in JetBrains | ☐ |
| Expand/collapse thinking | Ctrl+O | Only inside the panel | ☐ |
| Accept/Reject Change at Cursor, Rename/Group/Mark Unread tab, Show Logs, Logout, Focus last message, Open in Side Bar/Terminal/New Window | — | Actions without default keys | ☐ |

## L. Settings — Phase 14 (settings page)

| VS Code setting | Equivalent | Status |
|---|---|---|
| `useTerminal`, `initialPermissionMode`, `preferredLocation`, `lockEditorGroups`, `autosave`, `attachOpenFile`, `attachSelection`, `useCtrlEnterToSend`, `scrollToBottomOnSend`, `showMessageTimestamps`, `spinnerVerbs`, `enableNewConversationShortcut`, `enableReopenClosedSessionShortcut`, `archiveInactiveSessions`, `continueAfterReload`, `hideOnboarding`, `focusView`, `respectGitIgnore`, `environmentVariables`, `disableLoginPrompt`, `allowDangerouslySkipPermissions`, `claudeProcessWrapper` | Same names on our settings page | ☐ |
| `usePythonEnvironment` | Adapt: optional Rust toolchain environment (rustup toolchain / cargo env) | ☐ |
| (ours) Integration mode Built-in / Official / Auto, path to `claude` | Settings page | ☐ |

## M. Links, accessibility, providers — Phase 14

| Feature | Via | Status |
|---|---|---|
| URI handler `…/open?prompt=…&session=…` | Adapt: JetBrains protocol handler (`jetbrains://rustrover/…`) | ☐ |
| Screen reader announcements, labeled transcript, Focus last message | UI (JCEF accessibility + IDE announcements) | ☐ |
| Third-party providers (Bedrock, Vertex/Agent Platform, Foundry): `disableLoginPrompt`, no claude.ai-only features | UI + CLI | ☐ |
| Show Logs (extension debug logs) | UI (open `bridge.log` + IDE log) | ◐ (bridge.log exists) |

## N. Out of scope (for now)

| Feature | Why |
|---|---|
| Plugins that draw in the panel (mods: panes, toasts, status lines, custom message rendering) | Built on the VS Code extension's own panel API; no public contract for other hosts. Revisit only if one is published. |
| `executeCode` / Jupyter | No Python notebooks in RustRover. |
| Bundled private CLI copy | Your design requires the user's own installed CLI. |
