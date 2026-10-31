package dev.metaclaude.panel

import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import dev.metaclaude.protocol.EditProposal
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 8b: an Edit / MultiEdit / Write permission request from the chat panel, shown in the IDE diff viewer (the same viewer
 * as terminal mode's openDiff). Accept = allow (with your edits, if you changed the right side); Reject = deny. Closing the tab
 * decides nothing: the card in the panel stays open. Unlike openDiff, the plugin never writes the file: the CLI does, after allow.
 */
class PanelDiffs(private val project: Project, parent: Disposable) : Disposable {
    private val tabs = ConcurrentHashMap<String, VirtualFile>() // request id -> diff tab

    init {
        Disposer.register(parent, this)
    }

    /**
     * EDT. Returns false when there is nothing to show (no file path, or the edit doesn't apply to the current text).
     * [onAccept] gets null when the proposal was accepted as is, or the edited text (\n line ends).
     */
    fun open(id: String, tool: String, input: JsonObject, focus: Boolean, onAccept: (edited: String?) -> Unit, onReject: () -> Unit): Boolean {
        tabs[id]?.let { FileEditorManager.getInstance(project).openFile(it, focus); return true }
        val path = EditProposal.filePath(input)?.let { runCatching { Path.of(it) }.getOrNull() } ?: return false
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        val current = vf?.let { FileDocumentManager.getInstance().getDocument(it)?.text } ?: ""
        val proposed = EditProposal.proposed(tool, input, current) ?: return false

        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(path.fileName.toString())
        val factory = DiffContentFactory.getInstance()
        val left = factory.create(project, current, fileType)
        val right: DocumentContent = factory.createEditable(project, proposed, fileType)
        val title = "Claude: ${path.fileName}"
        val request = SimpleDiffRequest(title, left, right, if (vf == null) "New file" else "Current", "Proposed by Claude (editable)")

        val accept = {
            val edited = right.document.text
            close(id)
            onAccept(if (edited == proposed) null else edited)
        }
        val reject = {
            close(id)
            onReject()
        }
        request.putUserData(
            DiffUserDataKeys.CONTEXT_ACTIONS,
            listOf<AnAction>(
                DumbAwareAction.create("Accept Claude's Changes", AllIcons.Actions.Checked) { accept() },
                DumbAwareAction.create("Reject Claude's Changes", AllIcons.Actions.Cancel) { reject() },
            ),
        )
        DiffUtil.addNotification({ _ ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Info).apply {
                text("Claude wants to change ${path.fileName}. Edit the right side if you want, then:")
                createActionLabel("Accept") { accept() }
                createActionLabel("Reject") { reject() }
            }
        }, request)

        val file = ChainDiffVirtualFile(SimpleDiffRequestChain(request), title)
        tabs[id] = file
        FileEditorManager.getInstance(project).openFile(file, focus)
        return true
    }

    /** EDT. The request was answered (in the diff, the card, or cancelled by the CLI). */
    fun close(id: String) {
        val file = tabs.remove(id) ?: return
        FileEditorManager.getInstance(project).closeFile(file)
    }

    override fun dispose() = tabs.clear()
}
