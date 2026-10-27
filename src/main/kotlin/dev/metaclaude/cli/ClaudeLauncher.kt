package dev.metaclaude.cli

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import java.nio.file.Path

object ClaudeLauncher {
    /**
     * Interactive launch in a PTY — the same way every editor integration starts the CLI.
     * With [bridgePort] set, the CLI is pointed straight at OUR bridge (no discovery needed);
     * with null (official-plugin mode) we set no IDE env vars and leave discovery to the CLI.
     *
     * The working directory should be the project root: the CLI matches its real cwd against
     * lock-file workspaceFolders, so pass a toRealPath() (macOS /tmp -> /private/tmp).
     */
    fun spawnPty(claudePath: String, cwd: Path, bridgePort: Int?): PtyProcess {
        val env = HashMap(System.getenv())
        if (bridgePort != null) {
            env["CLAUDE_CODE_SSE_PORT"] = bridgePort.toString()
            env["ENABLE_IDE_INTEGRATION"] = "true"
        }
        return PtyProcessBuilder(arrayOf(claudePath))
            .setDirectory(cwd.toString())
            .setEnvironment(env)
            .setInitialColumns(120).setInitialRows(30)
            .start()
    }

    // SPIKE #1 (do this before building any chat UI): does the IDE bridge attach in headless mode?
    //   claude -p --input-format stream-json --output-format stream-json --verbose
    // with CLAUDE_CODE_SSE_PORT + ENABLE_IDE_INTEGRATION set. If it does NOT attach, a custom chat UI
    // can't use openDiff/selection; fall back to the PTY profile above (or `--ide`, see claude --help).
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
