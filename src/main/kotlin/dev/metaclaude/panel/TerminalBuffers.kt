package dev.metaclaude.panel

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import dev.metaclaude.protocol.BridgeLog
import dev.metaclaude.protocol.PanelContext
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Phase 9b: RustRover's terminal tabs for `@terminal:name` mentions. EDT only.
 *
 * Two kinds of terminal tab exist. The reworked terminal (default in 2026.2) shows its output in an IDE editor, so its text
 * is that editor's document, found through the tab's data context (public APIs only). The classic terminal is a
 * TerminalWidget; its `getText()` is experimental API. v0.21.0 tried only the second and got nothing from the reworked one.
 */
object TerminalBuffers {
    private const val TOOL_WINDOW = "Terminal"

    /** Mention tokens of the open terminal tabs ("Local (2)" -> "Local-(2)"). */
    fun tokens(project: Project): List<String> = contents(project).map { PanelContext.terminalToken(it.displayName ?: "") }
        .filter { it.isNotEmpty() }.distinct()

    /** Text of the tab whose token is [token], or null (logged: which step failed, never the text). */
    fun text(project: Project, token: String): String? {
        val content = contents(project).firstOrNull { PanelContext.terminalToken(it.displayName ?: "") == token }
            ?: return null.also { BridgeLog.log("@terminal: no tab named '$token'", "${project.name}/panel") }
        editorText(content)?.let {
            BridgeLog.log("@terminal '$token': read ${it.length} chars (reworked terminal)", "${project.name}/panel")
            return it
        }
        val widget = TerminalToolWindowManager.findWidgetByContent(content)
        val classic = widget?.let { w -> runCatching { w.getText().toString() }.getOrNull() }?.takeIf { it.isNotBlank() }
        BridgeLog.log("@terminal '$token': " + (classic?.let { "read ${it.length} chars (classic terminal)" } ?: "could not read the tab"), "${project.name}/panel")
        return classic
    }

    /** The reworked terminal's output editor, through the data context of the tab's components. */
    private fun editorText(content: Content): String? {
        val candidates = listOfNotNull(content.preferredFocusableComponent, content.component)
        for (c in candidates) {
            val editor = runCatching { DataManager.getInstance().getDataContext(c).getData(CommonDataKeys.EDITOR) }.getOrNull() ?: continue
            val text = editor.document.text
            if (text.isNotBlank()) return text
        }
        return null
    }

    private fun contents(project: Project): List<Content> =
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW)?.contentManager?.contents?.toList() ?: emptyList()
}
