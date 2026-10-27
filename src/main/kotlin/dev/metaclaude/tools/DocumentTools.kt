package dev.metaclaude.tools

import com.google.gson.JsonObject
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.metaclaude.protocol.IdeTool
import dev.metaclaude.protocol.Mcp
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** The file if it is open in an editor tab, else null. EDT. */
private fun openFileOrNull(project: Project, filePath: String): VirtualFile? {
    val vf = LocalFileSystem.getInstance().findFileByNioFile(Path.of(filePath)) ?: return null
    return vf.takeIf { FileEditorManager.getInstance(project).isFileOpen(it) }
}

private fun notOpen(filePath: String) =
    Mcp.text(gson.toJson(mapOf("success" to false, "message" to "Document not open: $filePath")))

class CheckDocumentDirty(private val project: Project) : IdeTool {
    override val name = "checkDocumentDirty"
    override val description = "Check if a document has unsaved changes (is dirty)"
    override val inputSchema = schema("filePath" to "string", required = listOf("filePath"))

    override fun call(args: JsonObject): CompletableFuture<JsonObject> {
        val filePath = args.get("filePath").asString
        return onEdt {
            val vf = openFileOrNull(project, filePath) ?: return@onEdt notOpen(filePath)
            val fdm = FileDocumentManager.getInstance()
            val dirty = fdm.getCachedDocument(vf)?.let { fdm.isDocumentUnsaved(it) } ?: false
            Mcp.text(gson.toJson(mapOf(
                "success" to true, "filePath" to filePath, "isDirty" to dirty, "isUntitled" to false,
            )))
        }
    }
}

class SaveDocument(private val project: Project) : IdeTool {
    override val name = "saveDocument"
    override val description = "Save a document with unsaved changes"
    override val inputSchema = schema("filePath" to "string", required = listOf("filePath"))

    override fun call(args: JsonObject): CompletableFuture<JsonObject> {
        val filePath = args.get("filePath").asString
        return onEdt {
            val vf = openFileOrNull(project, filePath) ?: return@onEdt notOpen(filePath)
            val fdm = FileDocumentManager.getInstance()
            fdm.getCachedDocument(vf)?.let { fdm.saveDocument(it) }
            Mcp.text(gson.toJson(mapOf(
                "success" to true, "filePath" to filePath, "saved" to true, "message" to "Document saved successfully",
            )))
        }
    }
}
