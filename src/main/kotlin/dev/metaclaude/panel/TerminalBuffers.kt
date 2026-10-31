package dev.metaclaude.panel

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import dev.metaclaude.protocol.BridgeLog
import dev.metaclaude.protocol.PanelContext
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import javax.swing.SwingUtilities

/**
 * Phase 9b: RustRover's terminal tabs for `@terminal:name` mentions. EDT only.
 *
 * Two kinds of terminal tab exist. The reworked terminal (default in 2026.2) shows its output in an IDE editor, so its text
 * is that editor's document, found among EditorFactory's editors inside the tab (public APIs only). The classic terminal is a
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
        BridgeLog.log("@terminal '$token': " + (classic?.let { "read ${it.length} chars (classic terminal)" } ?: "could not read the tab (${describe(content)})"), "${project.name}/panel")
        return classic
    }

    /**
     * The reworked terminal's output editor: every IDE editor (EditorFactory) whose component sits inside this tab. v0.21.1
     * asked the tab's data context instead and got no editor. If several (output, alternate screen), the showing one with
     * the most text wins.
     */
    private fun editorText(content: Content): String? {
        val root = content.component
        val editors = EditorFactory.getInstance().allEditors.filter { SwingUtilities.isDescendingFrom(it.component, root) }
        if (editors.isEmpty()) return null
        return editors.sortedWith(compareByDescending<com.intellij.openapi.editor.Editor> { it.component.isShowing }.thenByDescending { it.document.textLength })
            .firstOrNull()?.document?.text?.takeIf { it.isNotBlank() }
    }

    /** For the log when nothing could be read: what the tab is made of (class names only). */
    private fun describe(content: Content): String {
        val editors = EditorFactory.getInstance().allEditors.count { SwingUtilities.isDescendingFrom(it.component, content.component) }
        return "component=${content.component.javaClass.simpleName}, editors inside=$editors, " +
            "widget=${TerminalToolWindowManager.findWidgetByContent(content)?.javaClass?.simpleName}"
    }

    private fun contents(project: Project): List<Content> =
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW)?.contentManager?.contents?.toList() ?: emptyList()
}
