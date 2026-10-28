package dev.metaclaude

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.metaclaude.protocol.BridgeLog
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer

/**
 * Every new terminal tab in this project gets CLAUDE_CODE_SSE_PORT + ENABLE_IDE_INTEGRATION,
 * so `claude` started there connects to our bridge by itself: no /ide needed.
 * Registered with order="last" so our port wins if the official plugin sets the same variables.
 * External terminals (and WSL shells) still need /ide.
 */
class ClaudeTerminalCustomizer : LocalTerminalCustomizer() {
    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: Array<String>,
        envs: MutableMap<String, String>,
    ): Array<String> {
        val port = project.service<MetaClaudeService>().bridgePortForTerminal() ?: return command
        envs["CLAUDE_CODE_SSE_PORT"] = port.toString()
        envs["ENABLE_IDE_INTEGRATION"] = "true"
        BridgeLog.log("terminal opened: claude started there connects to port $port automatically", project.name)
        return command
    }
}
