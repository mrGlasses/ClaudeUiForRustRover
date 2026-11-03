# Changelog

Each release lists what changed for you. Versions before 1.0.0 were development builds, summarized by phase
(details in PLAN.md).

## [1.0.1] - 2026-11-03

- README: a screenshot of the chat panel.
- Getting started list: each bullet now sits on the first line of its step (it sat on the last line of wrapped steps).

## [1.0.0] - 2026-11-03

First public release, on GitHub Releases.

- README, changelog, and a release workflow: a `v*` tag builds, tests and publishes the zip with its SHA-256.
- The plugin accepts RustRover 2026.2 and later (no upper version limit).

## Development builds

### 0.39.0 – 0.41.0: hardening
- Long conversations: reopening a 3,100-item session went from 7.7 s to 0.5 s; long answers stream without slowing the
  panel; the history list reads a session again only when it changed.
- Security review (SECURITY.md): local servers refuse web pages and rebound domains; owner-only token files on Windows;
  the log keeps sizes, never tool replies, and redacts anything shaped like a token.
- Closing the IDE stops every chat process and what it started; leftover session files are cleaned up; the panel says
  when your Claude Code is older than the tested version.

### 0.38.0 – 0.38.1: usage ring
- A ring under the prompt fills with your plan's 5-hour usage; hover or click for every limit and its reset time.

### 0.36.0 – 0.37.0: Rust extras and the web
- Alt+Enter "Ask Claude to fix", Send to Claude from consoles, cargo check / clippy / test requests, Rust toolchain on PATH.
- Remote Control, teleport, `@browser` (Claude in Chrome).

### 0.33.0 – 0.35.0: IDE polish
- Status bar item, Tools > Claude menu, editor and selection "Ask Claude" buttons, panel location and more settings,
  onboarding, `/btw`, bookmarks, `jetbrains://` links, provider and process-wrapper settings.

### 0.29.0 – 0.32.0: Customize
- Commands, skills, output styles, hooks, permission rules, memory, instructions, MCP servers, plugins and
  marketplaces, sandbox and Chrome, all from the panel.

### 0.25.0 – 0.28.0: checkpoints, agents, model and usage
- Fork, rewind code, or both, from any prompt. Agent map with background tasks.
- Model, effort, thinking and Ultracode picker; context meter; prompt-cache clock; `/usage` and `/status`.

### 0.20.0 – 0.24.2: context and sessions
- `@` mentions (files, folders, terminals), images, dropped files, selection and open file as context.
- History with search, tabs, resume, rename, archive, groups, `/export`.

### 0.14.0 – 0.19.2: chat panel
- Streaming Markdown and LaTeX, tool cards, thinking, to-dos, focus view, timestamps, copy.
- Permission cards, diffs and plan documents in the panel, questions from Claude, permission modes.

### 0.5.0 – 0.13.0: IDE bridge and terminal mode
- Claude Code's IDE protocol: diff review with Accept / Reject, selection sharing, Alt+K mentions, diagnostics.
- Claude terminal tabs connected automatically; settings page; optional official plugin.
