package dev.metaclaude.cli

import java.nio.file.Path

object ClaudeLauncher {
    // Terminal mode runs claude in an IDE terminal tab (see ClaudeTerminal); the hidden PTY launch was removed in v0.10.0.

    // SPIKE (Phase 6): does the IDE bridge attach in headless mode?
    //   claude -p --input-format stream-json --output-format stream-json --verbose
    // with CLAUDE_CODE_SSE_PORT + ENABLE_IDE_INTEGRATION set. The chat panel can also hand the CLI its own
    // MCP server with --mcp-config, like VS Code's panel does (see PLAN.md, Part 2).
    fun spawnStreamJson(claudePath: String, cwd: Path, bridgePort: Int?): Process {
        val pb = ProcessBuilder(
            claudePath, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
        ).directory(cwd.toFile())
        if (bridgePort != null) {
            pb.environment()["CLAUDE_CODE_SSE_PORT"] = bridgePort.toString()
            pb.environment()["ENABLE_IDE_INTEGRATION"] = "true"
        }
        return pb.start()
    }
}
