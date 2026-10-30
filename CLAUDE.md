# Claude UI for RustRover — rules for Claude

A JetBrains (RustRover) plugin that gives the user's own `claude` CLI an IDE integration, aiming at parity with the
Claude Code VS Code extension. The official Anthropic JetBrains plugin is optional, detected at runtime, never a dependency.

Read first: `PLAN.md` (phases, current status), `PARITY.md` (feature checklist), `SKETCH.md` (architecture, protocol notes).

## How to work

1. **Think before coding.** State the goal of the change and how it will be checked before writing it.
   If something is ambiguous (protocol behavior, API signature), say so and plan how to verify it instead of guessing quietly.
2. **Simplicity first.** Write the smallest thing that meets the goal. Check in this order before adding anything:
   the IntelliJ Platform API → what the platform already bundles (Gson, pty4j, JCEF, diff viewer) → existing code → a new dependency (last resort).
3. **Surgical changes.** Touch only what the task needs. No drive-by refactors, renames or reformatting in the same commit.
4. **Goal-driven.** Every phase has a "Done when" in PLAN.md. A task is finished when that check passes, not when the code exists.
   For tiny fixes (one line, a typo) skip the ceremony.

## Never simplify these away

- **Every `openDiff` call gets exactly one reply**: `FILE_SAVED`, `DIFF_REJECTED` or `TAB_CLOSED`. Closing a window,
  `close_tab`, `closeAllDiffTabs`, project close and plugin unload must all resolve pending diffs. A missing reply hangs the CLI.
- **On Accept, never write the file**: the CLI writes it after `FILE_SAVED` (writing it too applied the edit twice in v0.6.1).
  The plugin only saves unsaved IDE edits before replying and refreshes the file afterwards.
- **Notifications have no reply; requests always do** (result or JSON-RPC error). Unknown methods with an id get `-32601`.
- **Security**: the server binds to loopback only; the auth header is compared in constant time; the token is fresh per IDE start;
  **never log the token or message bodies** (they can contain source code). Lock files: owner-only permissions.
- **Chat panel (JCEF web UI) security**, decided 2026-10-29; every rule applies from the first line of panel code:
  1. No HTML from outside runs: Markdown is rendered with raw HTML off, then sanitized (DOMPurify). Never `innerHTML` unsanitized text.
  2. Strict CSP: only scripts bundled in the plugin (nonce), nothing inline, no remote resources, no network requests from the panel.
  3. No navigation: the panel never leaves its page; link clicks go to Kotlin, which opens only `http`/`https` in the system browser.
  4. Narrow JS → Kotlin bridge: a closed set of message types, each validated in Kotlin (e.g. a permission answer only for a
     request that is actually pending). JS never gets file, process or network access.
  5. Native confirmation (IDE dialog, not the web panel) for risky actions: saving a permanent permission rule, bypass mode.
  6. DevTools off in release builds.
- **Windows paths**: the user is on Windows. Normalize drive-letter case and separators in everything sent to or compared with the CLI.
- **Threading**: UI and documents on the EDT / in write actions; never block the EDT waiting for the CLI.

## Process

- One phase (or sub-phase) per commit and per push. The user installs each build and tests it before the next phase.
- Bump `version` in `build.gradle.kts` on every change that will be pushed. The bridge logs it as its first line.
- Update `PLAN.md` status and the matching `PARITY.md` rows in the same commit as the feature.
- There is **no local JDK**: builds and tests run in GitHub Actions only (`.github/workflows/build.yml`). Code can't be compiled
  before pushing, so prefer well-known platform APIs, keep each change small, and add a unit test for protocol logic.
- Debug data lives in `%USERPROFILE%\.claude-meta\bridge.log`. Ask for it when something fails.

## Layout

- `protocol/`: lock file, WebSocket server, JSON-RPC/MCP dispatcher, log. Pure Java/Kotlin, unit-testable without the IDE.
- `tools/`: IDE tools exposed over MCP (need the IntelliJ Platform).
- `compat/`: official plugin detection and integration modes.
- `cli/`: launching the `claude` process.
- `src/test/`: tests that play the CLI side of the protocol.

## References

- Protocol (community, reverse-engineered): https://raw.githubusercontent.com/coder/claudecode.nvim/main/PROTOCOL.md
- VS Code feature reference: https://code.claude.com/docs/en/vs-code
- JetBrains integration docs: https://code.claude.com/docs/en/jetbrains
