# Security

Clawed UI connects your own `claude` CLI to RustRover. Claude Code can read, change and delete files and run commands
within the permissions you approve; this plugin adds IDE features around it and tries not to widen what can reach it.

## Reporting a problem

Please open a private report through GitHub's "Report a vulnerability" (Security tab of
https://github.com/mrGlasses/ClaudeUiForRustRover), not a public issue. Include the plugin version (first line of each
start in `%USERPROFILE%\.claude-meta\bridge.log`) and the steps to reproduce.

## What the plugin does to stay safe (review of 2026-11-03, v0.40.0)

**Local servers.** Two small servers let the CLI use IDE features: the IDE bridge (WebSocket, for `claude` in a
terminal) and the chat panel's MCP server (HTTP, one per conversation).
- Both listen on the loopback address only, on a port the OS picks.
- Both require a random 128-bit token, new at every IDE start (bridge) or conversation (panel), compared in constant time.
- Both refuse requests that carry an `Origin` header (a web page) or a `Host` that isn't 127.0.0.1, localhost or [::1]
  with the right port (DNS rebinding). The token is still required: this is a second lock.
- The panel server refuses bodies over 4 MB.

**Files holding a token.** The lock file (`~/.claude/ide/<port>.lock`, read by the CLI) and the panel's session files
(`~/.claude-meta/tmp`) are owner-only: POSIX permissions on Linux and macOS, an access list with only you on Windows
(skipped, and logged, when the IDE runs as another account, so you never lose access). They are deleted when the IDE
or the conversation closes, and files left by a crashed IDE are removed at the next start.

**Log.** `~/.claude-meta/bridge.log` holds events, tool names and sizes, never message bodies, file contents, prompts,
environment variable values or tokens. As a safety net, anything shaped like a token is replaced with `[redacted]`,
and each line is capped at 1000 characters. The log is capped at 1 MB (the previous part is kept as `bridge.log.1`).

**Chat panel (embedded browser).**
- Markdown is rendered with raw HTML off and then sanitized (DOMPurify); links are limited to http(s) and `#`.
- Content Security Policy: only the plugin's own scripts and styles (nonce), no remote resources, no network requests.
- The page never navigates: link clicks go to the IDE, which opens only http(s) URLs in your browser.
- The page can only send a fixed set of messages to the IDE, each checked there (e.g. a permission answer only for a
  request that is actually pending). It has no file, process or network access of its own.
- Risky actions ask in a native IDE dialog: saving a permanent permission rule, bypass mode, signing out, rewinding code.
- DevTools are off.

**Running the CLI.** Arguments are passed as a list, never through a shell string. When `claude` is a `.cmd`/`.bat`
(cmd.exe in between), free text with `"`, `%`, `^` or `!` is refused, since cmd.exe would interpret it; JDK quotes the
other cmd.exe characters (`&`, `|`, `<`, `>`). Settings like extra environment variables are logged by name only.

**Not covered.** What Claude does inside your project is decided by Claude Code's own permission system and your
answers. Another program running as your user account can read your files, tokens included; that is outside what an IDE
plugin can prevent.
