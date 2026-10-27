package dev.metaclaude.tools

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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import dev.metaclaude.protocol.BridgeLog
import dev.metaclaude.protocol.IdeTool
import dev.metaclaude.protocol.Mcp
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * openDiff: shows Claude's proposal in an editor tab and withholds the reply until the user decides.
 *
 *  Accept        → writes the (possibly edited) right side through the IDE, replies FILE_SAVED + final text
 *  Reject        → DIFF_REJECTED
 *  Tab closed    → DIFF_REJECTED (no decision means no change)
 *  close_tab     → TAB_CLOSED (the CLI closed it, e.g. you answered in the terminal)
 *  Project close → DIFF_REJECTED for everything still open
 *
 * Per-change review: the right side is editable, so the diff viewer's own arrows copy a single change back
 * from the current version (= reject that change). Untouched changes count as accepted.
 */
class DiffCoordinator(private val project: Project, parent: Disposable) : IdeTool, Disposable {
    override val name = "openDiff"
    override val description = "Open a diff for the file and wait for the user to accept or reject (blocking)"
    override val inputSchema = schema(
        "old_file_path" to "string", "new_file_path" to "string",
        "new_file_contents" to "string", "tab_name" to "string",
        required = listOf("old_file_path", "new_file_path", "new_file_contents", "tab_name"),
    )

    private val pending = PendingDiffs<JsonObject>()
    private val tabs = ConcurrentHashMap<String, VirtualFile>() // tab_name -> diff editor file

    init {
        Disposer.register(parent, this)
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    val tab = tabs.entries.firstOrNull { it.value == file }?.key ?: return
                    tabs.remove(tab)
                    if (pending.resolve(tab, DiffReply.rejected())) BridgeLog.log("diff '$tab' closed without a decision -> rejected")
                }
            },
        )
    }

    override fun call(args: JsonObject): CompletableFuture<JsonObject> {
        val tab = args.get("tab_name").asString
        val oldPath = Path.of(args.get("old_file_path").asString)
        val newPath = Path.of(args.get("new_file_path").asString)
        val proposal = args.get("new_file_contents").asString

        val future = pending.open(tab, DiffReply.rejected())
        BridgeLog.log("diff '$tab' opened for ${newPath.fileName}")
        ApplicationManager.getApplication().invokeLater {
            try {
                show(tab, oldPath, newPath, proposal)
            } catch (e: Throwable) {
                BridgeLog.log("diff '$tab' failed to open: ${e::class.java.name}: ${e.message}")
                pending.fail(tab, e) // the CLI gets a JSON-RPC error instead of waiting forever
            }
        }
        return future
    }

    /** EDT. */
    private fun show(tab: String, oldPath: Path, newPath: Path, proposal: String) {
        closeTab(tab) // a re-proposal for the same tab replaces the old window (its call was already answered)

        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(newPath.fileName.toString())
        val factory = DiffContentFactory.getInstance()
        val oldFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(oldPath)
        // Current text includes unsaved edits, if the file is open.
        val current = oldFile?.let { FileDocumentManager.getInstance().getDocument(it)?.text } ?: ""

        val left = factory.create(project, current, fileType)
        val right: DocumentContent = factory.createEditable(project, DiffReply.toDocument(proposal), fileType)
        val request = SimpleDiffRequest(
            "Claude: $tab", left, right,
            if (oldFile == null) "New file" else "Current", "Proposed by Claude (editable)",
        )

        val accept = DumbAwareAction.create("Accept Claude's Changes", AllIcons.Actions.Checked) {
            accept(tab, newPath, proposal, right.document.text)
        }
        val reject = DumbAwareAction.create("Reject Claude's Changes", AllIcons.Actions.Cancel) { reject(tab) }
        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf<AnAction>(accept, reject))

        val banner = EditorNotificationPanel(EditorNotificationPanel.Status.Info)
        banner.text("Claude proposes changes to ${newPath.fileName}. Edit the right side if you want, then:")
        banner.createActionLabel("Accept") { accept(tab, newPath, proposal, right.document.text) }
        banner.createActionLabel("Reject") { reject(tab) }
        DiffUtil.addNotification(banner, request)

        val file = ChainDiffVirtualFile(SimpleDiffRequestChain(request), "Claude: $tab")
        tabs[tab] = file
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** EDT. */
    private fun accept(tab: String, newPath: Path, proposal: String, edited: String) {
        if (!pending.isPending(tab)) return
        try {
            writeThroughIde(newPath, edited)
        } catch (e: Throwable) {
            BridgeLog.log("diff '$tab' accept failed: ${e::class.java.name}: ${e.message}")
            pending.fail(tab, e)
            closeTab(tab)
            return
        }
        pending.resolve(tab, DiffReply.saved(proposal, edited))
        BridgeLog.log("diff '$tab' accepted" + if (edited != DiffReply.toDocument(proposal)) " (edited by user)" else "")
        closeTab(tab)
    }

    /** EDT. */
    private fun reject(tab: String) {
        if (pending.resolve(tab, DiffReply.rejected())) BridgeLog.log("diff '$tab' rejected")
        closeTab(tab)
    }

    /** Document + VFS, so undo and open editors stay in sync (no raw file writes). EDT. */
    private fun writeThroughIde(path: Path, text: String) {
        val existing = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        WriteCommandAction.runWriteCommandAction(project, "Claude Edit", null, Runnable {
            val file = existing ?: run {
                val dir = VfsUtil.createDirectoryIfMissing(path.parent.toString())
                    ?: error("cannot create folder ${path.parent}")
                dir.createChildData(this@DiffCoordinator, path.fileName.toString())
            }
            val doc = FileDocumentManager.getInstance().getDocument(file) ?: error("${path.fileName} is not a text file")
            doc.setText(text)
            FileDocumentManager.getInstance().saveDocument(doc)
        })
    }

    /** EDT. Closing our own tab never answers twice: the listener only answers if still pending. */
    private fun closeTab(tab: String): Boolean {
        val file = tabs.remove(tab) ?: return false
        FileEditorManager.getInstance(project).closeFile(file)
        return true
    }

    /** close_tab from the CLI. */
    fun closeFromCli(tab: String) {
        pending.resolve(tab, DiffReply.tabClosed())
        ApplicationManager.getApplication().invokeLater { closeTab(tab) }
    }

    /** closeAllDiffTabs from the CLI. Returns how many of our diff tabs were open. */
    fun closeAllFromCli(): Int {
        val names = tabs.keys.toList()
        names.forEach { pending.resolve(it, DiffReply.tabClosed()) }
        ApplicationManager.getApplication().invokeLater { names.forEach { closeTab(it) } }
        return names.size
    }

    override fun dispose() {
        val n = pending.resolveAll(DiffReply.rejected())
        if (n > 0) BridgeLog.log("$n pending diff(s) answered as rejected on shutdown")
        tabs.clear()
    }
}

class CloseTab(private val diffs: DiffCoordinator) : IdeTool {
    override val name = "close_tab" // snake_case on purpose: that's what the CLI calls
    override val description = "Close a tab by name"
    override val inputSchema = schema("tab_name" to "string", required = listOf("tab_name"))
    override fun call(args: JsonObject): CompletableFuture<JsonObject> {
        diffs.closeFromCli(args.get("tab_name").asString)
        return CompletableFuture.completedFuture(DiffReply.tabClosed())
    }
}

class CloseAllDiffTabs(private val diffs: DiffCoordinator) : IdeTool {
    override val name = "closeAllDiffTabs"
    override val description = "Close all diff tabs in the editor"
    override val inputSchema = schema()
    override fun call(args: JsonObject): CompletableFuture<JsonObject> =
        CompletableFuture.completedFuture(Mcp.text("CLOSED_${diffs.closeAllFromCli()}_DIFF_TABS"))
}
