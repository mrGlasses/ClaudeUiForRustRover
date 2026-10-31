package dev.metaclaude

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import dev.metaclaude.protocol.CliPaths

/**
 * Alt+K / editor right-click "Send Selection to Claude": inserts @file#start-end into the connected
 * session's prompt (at_mentioned). Without a selection, sends the cursor's line.
 */
class SendAtMentionAction : DumbAwareAction() {
    /** The panel class exists only with JCEF (claude-ui-jcef.xml); without it this always answers false. */
    private fun insertIntoPanel(project: com.intellij.openapi.project.Project, file: com.intellij.openapi.vfs.VirtualFile, startLine: Int, endLine: Int): Boolean {
        if (runCatching { Class.forName("dev.metaclaude.panel.ChatPanel") }.isFailure) return false
        val root = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        val rel = root?.let { com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, it) } ?: file.path
        val lines = if (startLine == endLine) "${startLine + 1}" else "${startLine + 1}-${endLine + 1}"
        return dev.metaclaude.panel.ChatPanel.insertMention(project, "@$rel#$lines ")
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val doc = editor.document
        val sm = editor.selectionModel
        val start = if (sm.hasSelection()) sm.selectionStart else editor.caretModel.offset
        val end = if (sm.hasSelection()) sm.selectionEnd else editor.caretModel.offset
        val startLine = doc.getLineNumber(start)
        var endLine = doc.getLineNumber(end)
        // A selection that ends at column 0 doesn't include that last line.
        if (end > start && endLine > startLine && end == doc.getLineStartOffset(endLine)) endLine--

        // The chat panel, when it's showing, gets "@path#5-10" in its prompt box (Phase 9); otherwise a terminal session.
        if (insertIntoPanel(project, file, startLine, endLine)) return
        val sent = project.service<MetaClaudeService>().sendAtMention(CliPaths.forCli(file.path), startLine, endLine)
        if (!sent) {
            NotificationGroupManager.getInstance().getNotificationGroup("Clawed UI")
                .createNotification(
                    "No Claude session connected",
                    "Run claude in a terminal, then /ide and pick ${PluginInfo.IDE_NAME}.",
                    NotificationType.INFORMATION,
                )
                .notify(project)
        }
    }
}
