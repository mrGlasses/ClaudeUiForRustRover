package dev.metaclaude.panel

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.metaclaude.protocol.RustHelp

/**
 * Phase 15a, Rust extras: Alt+Enter "Ask Claude to fix" on a highlighted problem, "Send to Claude" from a Run / cargo console,
 * and ready-made cargo check / clippy / test requests. Each one fills the chat's message box; you send it.
 */
class AskClaudeToFixIntention : IntentionAction, PriorityAction {
    override fun getText() = "Ask Claude to fix"
    override fun getFamilyName() = "Ask Claude to fix"
    override fun startInWriteAction() = false
    override fun getPriority() = PriorityAction.Priority.LOW

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && problemsAtCaret(project, editor).isNotEmpty()

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        editor ?: return
        val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val problems = problemsAtCaret(project, editor)
        if (problems.isEmpty()) return
        val root = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).getContentRootForFile(vf)
        val rel = root?.let { com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(vf, it) } ?: vf.path
        AskClaudeAction.insert(project, RustHelp.fixPrompt(rel, problems))
    }

    /** Errors and warnings that touch the caret's line (or the selected lines). */
    private fun problemsAtCaret(project: Project, editor: Editor): List<RustHelp.Problem> {
        val doc = editor.document
        val sm = editor.selectionModel
        val first = doc.getLineNumber(if (sm.hasSelection()) sm.selectionStart else editor.caretModel.offset)
        val last = doc.getLineNumber(if (sm.hasSelection()) sm.selectionEnd else editor.caretModel.offset)
        val start = doc.getLineStartOffset(first)
        val end = doc.getLineEndOffset(last)
        val out = ArrayList<RustHelp.Problem>()
        DaemonCodeAnalyzerEx.processHighlights(doc, project, HighlightSeverity.WARNING, start, end) { info ->
            val msg = info.description
            if (!msg.isNullOrBlank() && out.size < 10) {
                out += RustHelp.Problem(
                    if (info.severity >= HighlightSeverity.ERROR) "error" else "warning",
                    msg,
                    doc.getLineNumber(info.startOffset.coerceIn(0, doc.textLength)) + 1,
                )
            }
            true
        }
        return out
    }
}

/** Console (Run, cargo, tests) right-click: the selection, or the end of the output, to the chat's message box. */
class SendConsoleToClaudeAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val text = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() } ?: editor.document.text
        if (text.isBlank()) return
        val title = e.getData(com.intellij.openapi.actionSystem.LangDataKeys.RUN_CONTENT_DESCRIPTOR)?.displayName
        AskClaudeAction.insert(project, RustHelp.consolePrompt(title, text))
    }
}

/** Tools > Claude > Fix cargo check errors / clippy warnings / failing tests. */
abstract class CargoRequestAction(private val key: String) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AskClaudeAction.insert(project, RustHelp.QUICK.getValue(key))
    }
}
class CargoCheckAction : CargoRequestAction("check")
class CargoClippyAction : CargoRequestAction("clippy")
class CargoTestAction : CargoRequestAction("test")
