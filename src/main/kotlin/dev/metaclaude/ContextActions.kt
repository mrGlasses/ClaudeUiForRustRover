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
