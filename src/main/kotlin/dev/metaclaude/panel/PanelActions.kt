package dev.metaclaude.panel

import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Consumer
import dev.metaclaude.protocol.BridgeLog
import java.awt.event.MouseEvent

/**
 * Phase 14a: entry points to the chat panel, like the VS Code extension's (Activity Bar, editor toolbar spark, status bar
 * item, Command Palette commands). All in Tools > Claude and in Find Action (Ctrl+Shift+A); none has a global key by default,
 * so no JetBrains shortcut is taken (Ctrl+N and Ctrl+Shift+T work inside the panel only).
 */
object ClawedIcons {
    @JvmField val Clawed = IconLoader.getIcon("/icons/clawed.svg", ClawedIcons::class.java)
}

private fun tabs(e: AnActionEvent): ChatTabs? = e.project?.service<ChatTabs>()

/** Opens (or brings forward) the Claude Chat tool window. */
class OpenChatAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun actionPerformed(e: AnActionEvent) { tabs(e)?.toolWindow()?.activate(null) }
}

class NewConversationAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        val t = tabs(e) ?: return
        val window = t.toolWindow() ?: return
        // The window builds its tabs on first show; open it first, then add the tab.
        window.activate({ t.newTab(null) }, true)
    }
}

class ReopenClosedConversationAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun actionPerformed(e: AnActionEvent) { tabs(e)?.reopenClosed() }
}

/**
 * The editor toolbar "spark" and the selection's floating toolbar: puts `@file#lines` for the selection (or just `@file`)
 * in the chat's message box and opens the chat.
 */
class AskClaudeAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
        e.presentation.icon = ClawedIcons.Clawed
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val root = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        val rel = root?.let { com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, it) } ?: file.path
        val sm = editor.selectionModel
        val mention = if (sm.hasSelection()) {
            val doc = editor.document
            val start = doc.getLineNumber(sm.selectionStart)
            var end = doc.getLineNumber(sm.selectionEnd)
            if (end > start && sm.selectionEnd == doc.getLineStartOffset(end)) end--
            "@$rel#${if (start == end) "${start + 1}" else "${start + 1}-${end + 1}"} "
        } else "@$rel "
        insert(project, mention)
    }

    companion object {
        /** Opens the chat if needed, then inserts [text] into the selected conversation's message box. */
        fun insert(project: Project, text: String) {
            val t = project.service<ChatTabs>()
            val window = t.toolWindow() ?: return
            window.activate({
                if (!ChatPanel.insertMention(project, text)) t.selected()?.let { p ->
                    p.post(JsonObject().apply { addProperty("type", "insertText"); addProperty("text", text) })
                }
            }, true)
        }
    }
}

/** Show Logs: the plugin's own bridge.log in the editor (the IDE log is Help > Show Log). */
class ShowLogsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(BridgeLog.path) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }
}

/** Status bar item "✻ Claude", with the busiest state of this project's conversations. Click opens the chat. */
class ClaudeStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = ID
    override fun getDisplayName() = "Claude (Clawed UI)"
    override fun createWidget(project: Project): StatusBarWidget = ClaudeStatusWidget(project)

    companion object {
        const val ID = "ClawedUiStatus"
        fun refresh(project: Project) = WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
    }
}

private class ClaudeStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    override fun ID() = ClaudeStatusWidgetFactory.ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) {}
    override fun dispose() {}
    override fun getAlignment() = java.awt.Component.CENTER_ALIGNMENT
    override fun getText(): String = when (project.service<ChatTabs>().overallState()) {
        ChatTabs.Overall.NEEDS_YOU -> "✻ Claude · needs you"
        ChatTabs.Overall.WORKING -> "✻ Claude · working"
        ChatTabs.Overall.IDLE -> "✻ Claude"
    }
    override fun getTooltipText() = "Open the Claude chat"
    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { project.service<ChatTabs>().toolWindow()?.activate(null) }
}

/** Phase 14c, VS Code's "Focus last message": the newest message of the selected conversation gets keyboard focus. */
class FocusLastMessageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        val t = tabs(e) ?: return
        t.toolWindow()?.activate({ t.selected()?.postWhenReady(JsonObject().apply { addProperty("type", "focusLast") }) }, true)
    }
}
