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
import com.intellij.util.concurrency.AppExecutorUtil
import dev.metaclaude.protocol.BridgeLog
import dev.metaclaude.protocol.IdeTool
import dev.metaclaude.protocol.Mcp
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * openDiff: shows Claude's proposal in an editor tab and withholds the reply until the user decides.
 *
 *  Accept        → replies FILE_SAVED + final (possibly edited) text; the CLI writes the file, we only refresh
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

        // The platform asks a provider for the banner each time it builds the diff viewer.
        DiffUtil.addNotification({ _ ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Info).apply {
                text("Claude proposes changes to ${newPath.fileName}. Edit the right side if you want, then:")
                createActionLabel("Accept") { accept(tab, newPath, proposal, right.document.text) }
                createActionLabel("Reject") { reject(tab) }
            }
        }, request)

        val file = ChainDiffVirtualFile(SimpleDiffRequestChain(request), "Claude: $tab")
        tabs[tab] = file
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /**
     * EDT. The CLI writes the file itself after FILE_SAVED, so we must NOT write it too
     * (v0.6.1 did, and the CLI's edit was applied twice). We only make sure the IDE has no
     * unsaved changes to that file, then refresh it so open editors show the CLI's write.
     */
    private fun accept(tab: String, newPath: Path, proposal: String, edited: String) {
        if (!pending.isPending(tab)) return
        saveIfUnsaved(newPath)
        pending.resolve(tab, DiffReply.saved(proposal, edited))
        BridgeLog.log("diff '$tab' accepted" + if (edited != DiffReply.toDocument(proposal)) " (edited by user)" else "")
        closeTab(tab)
        refreshSoon(newPath)
    }

    /** EDT. */
    private fun reject(tab: String) {
        if (pending.resolve(tab, DiffReply.rejected())) BridgeLog.log("diff '$tab' rejected")
        closeTab(tab)
    }

    /** Unsaved IDE edits would clash with the CLI's write to disk. EDT. */
    private fun saveIfUnsaved(path: Path) {
        val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return
        val fdm = FileDocumentManager.getInstance()
        fdm.getCachedDocument(file)?.let { if (fdm.isDocumentUnsaved(it)) fdm.saveDocument(it) }
    }

    /** Picks up the CLI's write (or new file) without waiting for the file watcher. Background thread. */
    private fun refreshSoon(path: Path) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                ?.let { VfsUtil.markDirtyAndRefresh(true, false, false, it) }
        }, 1, TimeUnit.SECONDS)
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
