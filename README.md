# Clawed UI

A RustRover plugin for **your own Claude Code CLI**: a chat panel with everything the Claude Code VS Code extension
offers, diff review in the IDE, selections and diagnostics shared with Claude, and a few Rust-specific extras.
No Node.js needed, and the official Claude Code JetBrains plugin is optional.

> **Unofficial community plugin**, not affiliated with Anthropic or JetBrains. Claude Code can read, change and delete
> files and run commands within the permissions you approve: review every change and keep your work in version control.
> See [DISCLAIMER.md](DISCLAIMER.md).

## Requirements

- **RustRover 2026.2** or newer (build 262+). Other JetBrains IDEs of the same version may work but aren't tested.
- **Claude Code** installed (`claude` on your PATH, or its path set in the plugin's settings) and signed in.
  Tested with Claude Code 2.1.316; the panel tells you if yours is older.
- Windows, macOS or Linux. Development and testing happen on Windows.

## Install

1. Download `clawed-ui-<version>.zip` from the [latest release](https://github.com/mrGlasses/ClaudeUiForRustRover/releases/latest)
   (not the "Source code" zip).
2. Optional: check it against `SHA256SUMS.txt` from the same release
   (PowerShell: `Get-FileHash clawed-ui-<version>.zip`).
3. RustRover: **Settings > Plugins > ⚙ > Install Plugin from Disk…**, pick the zip, restart.
4. Open the **Claude Chat** tool window (right side bar, or **Tools > Claude > Open Chat**) and send a message.

To update, install the new zip the same way.

## What you get

**Chat panel** (Claude Chat tool window)
- Streaming answers with Markdown, code blocks and LaTeX; thinking, tool calls and subagents as they happen.
- Several conversations in tabs; history with search, rename, archive and groups; resume, fork and rewind code to any prompt.
- Permission cards with "save to" scopes, side-by-side diffs you can edit before accepting, plan documents you can comment on.
- `@` mentions for files, folders, terminals and `@browser`; pasted images and dropped files; your selection and open file as context.
- Model, effort and extended-thinking picker, context meter (`/compact`), prompt-cache clock, plan usage ring and `/usage`.
- Agent map with background tasks; `/btw` side questions; bookmarks; `/export`; focus view.
- **Customize** (••• menu): commands, skills, output styles, hooks, permission rules, memory, CLAUDE.md files, MCP servers,
  plugins and marketplaces, sandbox, Claude in Chrome.
- Remote Control (continue on claude.ai) and teleport (continue a claude.ai session here).

**Terminal mode**
- **Tools > New Claude Session** opens `claude` in a terminal tab, already connected to the IDE (no `/ide` needed).
- Diffs open in the IDE for review; the selection and diagnostics reach Claude; **Alt+K** inserts an `@file#lines` mention.

**Rust extras**
- **Alt+Enter > Ask Claude to fix** on any highlighted problem.
- **Send to Claude** from the Run or console output (right-click).
- **Tools > Claude > Fix cargo check errors / clippy warnings / failing tests**: puts a request in the message box; when you send it, Claude runs the command and fixes what it reports.

**IDE**
- "✻ Claude" status bar item, editor toolbar and selection "Ask Claude" buttons, Find Action entries, Windows-friendly
  paths, and links like `jetbrains://rustrover/clawed-ui/open?prompt=…`.

The full feature list, with what is and isn't covered compared to VS Code, is in [PARITY.md](PARITY.md).

## Settings

**Settings > Tools > Clawed UI**: the `claude` path (with a Test button), integration mode (built-in bridge or the
official plugin), panel location, send key, timestamps, thinking summaries, to-do list, extra environment variables,
a process wrapper, the Rust toolchain folder, and more.

## Troubleshooting

- The plugin writes `%USERPROFILE%\.claude-meta\bridge.log` (macOS/Linux: `~/.claude-meta/bridge.log`). It records
  events, never your messages, code or tokens. Attach it to a bug report.
- "Claude CLI not found": set the path in the settings and press **Test**.
- A terminal `claude` doesn't see the IDE: run `/ide` in it, and check that the integration mode is "built-in".
- Something in the panel misbehaves after a Claude Code update: run `claude update`, start a new conversation, and
  check for a newer Clawed UI release.
- Plugin problems: **••• > Report a Clawed UI problem** opens a GitHub issue with the versions filled in.

## Security

Loopback-only servers with per-session tokens, owner-only token files, a sandboxed panel (sanitized Markdown, strict CSP,
no navigation) and a log that never holds message bodies. Details and how to report a vulnerability: [SECURITY.md](SECURITY.md).

## Building

Builds run in GitHub Actions (`.github/workflows/build.yml`) on every push; the plugin zip is attached to each run.
To build locally without installing Java, see [BUILD.md](BUILD.md). Releases: push a tag `v<version>` matching the
version in `build.gradle.kts` (and a matching section in CHANGELOG.md), and `.github/workflows/release.yml` builds, tests and publishes it.

## License

[Apache License 2.0](LICENSE). See also [NOTICE](NOTICE) and [DISCLAIMER.md](DISCLAIMER.md).
"Claude" and "Claude Code" are trademarks of Anthropic; "RustRover" is a trademark of JetBrains.
