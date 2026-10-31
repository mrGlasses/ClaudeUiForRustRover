package dev.metaclaude.panel

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import dev.metaclaude.protocol.CliPaths
import dev.metaclaude.protocol.PanelContext
import dev.metaclaude.settings.ClaudeUiSettings
import dev.metaclaude.tools.EditorContext

/**
 * Phase 9: the open file and selection for the chat panel (VS Code's chips above the prompt). The page gets a short
 * description to show; the text itself is read again when a message is sent, and only if settings and ignore rules allow.
 */
class PanelContextTracker(private val project: Project, parent: Disposable, private val send: (JsonObject) -> Unit) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var lastSent: String? = null

    /** What would be attached right now (EDT). */
    class Snapshot(val openFile: String?, val selection: PanelContext.Selection?, val chip: JsonObject)

    init {
        Disposer.register(parent, this)
        val m = EditorFactory.getInstance().eventMulticaster
        m.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) { if (e.editor.project == project) schedule() }
        }, this)
        m.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) { if (event.editor.project == project) schedule() }
        }, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = schedule()
        })
    }

    private fun schedule() {
        alarm.cancelAllRequests()
        alarm.addRequest({ push(force = false) }, 200)
    }

    /** EDT. Sends the chip description when it changed (or always with [force], e.g. when the page loads). */
    fun push(force: Boolean) {
        if (project.isDisposed) return
        val chip = snapshot().chip
        val key = chip.toString()
        if (!force && key == lastSent) return
        lastSent = key
        send(chip)
    }

    /** EDT. */
    fun snapshot(): Snapshot {
        val settings = ClaudeUiSettings.instance.state
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }?.takeIf { it.isInLocalFileSystem }
        val chip = JsonObject().apply { addProperty("type", "context") }
        if (editor == null || file == null) return Snapshot(null, null, chip)

        val shareable = EditorContext.shareable(project, file, settings.respectGitIgnore)
        val cliPath = CliPaths.forCli(file.path)
        val sm = editor.selectionModel
        var selection: PanelContext.Selection? = null
        if (sm.hasSelection()) {
            val doc = editor.document
            val start = doc.getLineNumber(sm.selectionStart)
            var end = doc.getLineNumber(sm.selectionEnd)
            if (end > start && sm.selectionEnd == doc.getLineStartOffset(end)) end-- // ends at column 0: that line isn't selected
            val text = if (settings.attachSelection && shareable) sm.selectedText else null
            selection = PanelContext.Selection(cliPath, start + 1, end + 1, text)
        }
        // Like terminal mode: the open file travels alone only with attachOpenFile; with a selection it always does.
        val openFile = cliPath.takeIf { selection != null || settings.attachOpenFile }
        chip.addProperty("file", relative(file))
        chip.addProperty("showFile", openFile != null)
        selection?.let {
            chip.addProperty("startLine", it.startLine)
            chip.addProperty("endLine", it.endLine)
            chip.addProperty("textShared", it.text != null)
        }
        return Snapshot(openFile, selection, chip)
    }

    private fun relative(file: VirtualFile): String {
        val root = ProjectFileIndex.getInstance(project).getContentRootForFile(file) // EDT: reading is allowed here
        return root?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name
    }

    override fun dispose() = Unit
}
