package dev.metaclaude.tools

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
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
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import dev.metaclaude.protocol.BridgeLog
import dev.metaclaude.protocol.CliPaths
import dev.metaclaude.protocol.ContextJson
import dev.metaclaude.settings.ClaudeUiSettings
import java.util.concurrent.TimeUnit

/** Reads the editor's selection/cursor as the CLI expects it. EDT. */
internal object EditorContext {
    fun snapshot(project: Project, editor: Editor): JsonObject? {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        if (!file.isInLocalFileSystem) return null
        val doc = editor.document
        val sm = editor.selectionModel
        val caret = editor.caretModel.offset
        val settings = ClaudeUiSettings.instance.state
        // Text only when the user allows it and the file may be shared; otherwise path and cursor only.
        val sendText = settings.attachSelection && shareable(project, file, settings.respectGitIgnore)
        val start = if (sm.hasSelection() && sendText) sm.selectionStart else caret
        val end = if (sm.hasSelection() && sendText) sm.selectionEnd else caret
        val sl = doc.getLineNumber(start)
        val el = doc.getLineNumber(end)
        return ContextJson.selection(
            text = if (start != end) sm.selectedText ?: "" else "",
            filePath = CliPaths.forCli(file.path),
            startLine = sl, startChar = start - doc.getLineStartOffset(sl),
            endLine = el, endChar = end - doc.getLineStartOffset(el),
        )
    }

    /** Inside the project, not excluded, and (if [respectGitIgnore]) not git-ignored. */
    fun shareable(project: Project, file: VirtualFile, respectGitIgnore: Boolean): Boolean {
        val index = ProjectFileIndex.getInstance(project)
        if (!index.isInContent(file) || index.isExcluded(file)) return false
        return !respectGitIgnore || !ChangeListManager.getInstance(project).isIgnoredFile(file)
    }
}

/**
 * Sends selection_changed (current selection, or cursor + active file) to the connected CLI.
 * - debounced: one notification after the editor stops changing for [DEBOUNCE_MS]
 * - silent for [CONNECT_DELAY_MS] after ide_connected (the CLI drops notifications sent right after it,
 *   see github.com/anthropics/claude-code/issues/99268), then sends the current state once
 * - identical consecutive states are not re-sent
 */
class SelectionTracker(
    private val project: Project,
    parent: Disposable,
    private val send: (method: String, params: JsonObject) -> Unit,
) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    @Volatile private var ready = false
    @Volatile private var lastSent: String? = null

    /** Last non-empty selection, for getLatestSelection. */
    @Volatile var latest: JsonObject? = null
        private set

    init {
        Disposer.register(parent, this)
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = onEditorEvent(e.editor)
        }, this)
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = onEditorEvent(event.editor)
        }, this)
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = schedule()
            },
        )
    }

    private fun onEditorEvent(editor: Editor) {
        if (editor.project == project) schedule()
    }

    private fun schedule() {
        alarm.cancelAllRequests()
        alarm.addRequest({ sendCurrent() }, DEBOUNCE_MS)
    }

    /** Called from the WebSocket thread when the CLI says ide_connected. */
    fun onIdeConnected() {
        ready = false
        lastSent = null
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ready = true
            BridgeLog.log("selection sharing active", project.name)
            ApplicationManager.getApplication().invokeLater { sendCurrent() }
        }, CONNECT_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /** Current selection of the active editor, or null. EDT. */
    fun current(): JsonObject? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return EditorContext.snapshot(project, editor)
    }

    /** EDT. */
    private fun sendCurrent() {
        if (project.isDisposed) return
        val snap = current() ?: return
        val empty = snap.getAsJsonObject("selection").get("isEmpty").asBoolean
        if (!empty) latest = snap
        if (!ready) return
        if (empty && !ClaudeUiSettings.instance.state.attachOpenFile) return // open file only travels with a selection
        val key = snap.toString()
        if (key == lastSent) return
        lastSent = key
        send("selection_changed", snap)
    }

    override fun dispose() = Unit

    companion object {
        const val DEBOUNCE_MS = 200
        const val CONNECT_DELAY_MS = 500L
    }
}
