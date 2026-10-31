package dev.metaclaude.panel

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.metaclaude.protocol.PanelContext
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/** Phase 9b: RustRover's terminal tabs for `@terminal:name` mentions. EDT only. */
object TerminalBuffers {
    private const val TOOL_WINDOW = "Terminal"

    /** Mention tokens of the open terminal tabs ("Local (2)" -> "Local-(2)"). */
    fun tokens(project: Project): List<String> = contents(project).map { PanelContext.terminalToken(it.displayName ?: "") }
        .filter { it.isNotEmpty() }.distinct()

    /** Text of the tab whose token is [token], or null when there's no such tab (or it can't be read). */
    fun text(project: Project, token: String): String? {
        val content = contents(project).firstOrNull { PanelContext.terminalToken(it.displayName ?: "") == token } ?: return null
        val widget = TerminalToolWindowManager.findWidgetByContent(content) ?: return null
        return runCatching { widget.getText().toString() }.getOrNull()
    }

    private fun contents(project: Project) =
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW)?.contentManager?.contents?.toList() ?: emptyList()
}
