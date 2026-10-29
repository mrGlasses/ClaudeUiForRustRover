package dev.metaclaude

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Path

/**
 * Terminal mode (VS Code's `useTerminal`): each session is a "Claude" tab in RustRover's Terminal tool window.
 * The terminal customizer already points new tabs at our bridge, so `claude` connects without /ide.
 */
object ClaudeTerminal {
    const val TOOL_WINDOW_ID = "Terminal"
    private const val TAB_NAME = "Claude"

    /** EDT. [command] comes from SessionCommand, built off the EDT (the shell lookup may block). */
    fun open(project: Project, root: Path, command: String) {
        val widget = TerminalToolWindowManager.getInstance(project)
            .createShellWidget(root.toString(), TAB_NAME, true, true)
        widget.sendCommandToExecute(command)
    }

    fun toolWindow(project: Project): ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)

    /** The newest terminal tab running Claude. Claude renames its tab (e.g. "✳ Claude Code"), so match loosely. */
    fun claudeTab(project: Project): Content? =
        toolWindow(project)?.contentManager?.contents?.lastOrNull { it.displayName?.contains("Claude", ignoreCase = true) == true }
}

/**
 * Alt+Shift+K: editor → Claude tab, Claude tab → editor. Opens a session if there is none.
 * (VS Code uses Ctrl+Esc, but on Windows that key opens the Start menu before the IDE sees it.)
 */
class FocusClaudeAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ClaudeTerminal.toolWindow(project)
        val tab = ClaudeTerminal.claudeTab(project)
        when {
            toolWindow == null || tab == null -> project.service<MetaClaudeService>().startSession()
            toolWindow.isActive && toolWindow.contentManager.selectedContent == tab ->
                ToolWindowManager.getInstance(project).activateEditorComponent()
            else -> toolWindow.activate({ toolWindow.contentManager.setSelectedContent(tab, true) }, true)
        }
    }
}
