package dev.metaclaude.panel

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComponent

/**
 * Phase 8b: Claude's plan (ExitPlanMode) opened as an editable Markdown document. Comment anywhere in it (or change it),
 * then "Send my comments" (keep planning, Claude gets the annotated plan) or "Approve plan". Closing the tab decides nothing.
 */
class PlanDocuments(private val project: Project, parent: Disposable) : Disposable {
    class Handle(val original: String, val approve: () -> Unit, val sendComments: (String) -> Unit)

    private val docs = ConcurrentHashMap<String, LightVirtualFile>() // request id -> document

    init {
        Disposer.register(parent, this)
    }

    /** EDT. */
    fun open(id: String, plan: String, onApprove: () -> Unit, onComments: (String) -> Unit) {
        docs[id]?.let { FileEditorManager.getInstance(project).openFile(it, true); return }
        val file = LightVirtualFile("Claude plan.md", FileTypeManager.getInstance().getFileTypeByFileName("plan.md"), plan)
        file.putUserData(HANDLE, Handle(plan, { close(id); onApprove() }, { text -> close(id); onComments(text) }))
        docs[id] = file
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** EDT. */
    fun close(id: String) {
        val file = docs.remove(id) ?: return
        file.putUserData(HANDLE, null)
        FileEditorManager.getInstance(project).closeFile(file)
    }

    override fun dispose() = docs.clear()

    companion object {
        val HANDLE = Key.create<Handle>("clawed.ui.plan")
    }
}

/** The banner on a plan document (registered in claude-ui-jcef.xml). */
class PlanNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val handle = file.getUserData(PlanDocuments.HANDLE) ?: return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text("Claude's plan. Add your comments anywhere in the text (or change it), then:")
                createActionLabel("Send my comments") {
                    val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)?.text ?: handle.original
                    handle.sendComments(text)
                }
                createActionLabel("Approve plan") { handle.approve() }
            }
        }
    }
}
