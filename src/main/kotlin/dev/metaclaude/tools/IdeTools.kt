package dev.metaclaude.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.metaclaude.protocol.IdeTool
import dev.metaclaude.protocol.Mcp
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

// Tool names/shapes follow coder/claudecode.nvim PROTOCOL.md (reverse-engineered from the VS Code extension).
// Diff tools live in DiffTools.kt, document tools in DocumentTools.kt.

internal val gson = Gson()

/** Runs [block] on the EDT; exceptions become a failed future (→ JSON-RPC error, never a hang). */
internal fun onEdt(block: () -> JsonObject): CompletableFuture<JsonObject> {
    val f = CompletableFuture<JsonObject>()
    ApplicationManager.getApplication().invokeLater {
        try { f.complete(block()) } catch (e: Throwable) { f.completeExceptionally(e) }
    }
    return f
}

internal fun schema(vararg props: Pair<String, String>, required: List<String> = emptyList()) = JsonObject().apply {
    addProperty("type", "object")
    add("properties", JsonObject().apply {
        props.forEach { (k, t) -> add(k, JsonObject().apply { addProperty("type", t) }) }
    })
    add("required", gson.toJsonTree(required))
}

class GetWorkspaceFolders(private val project: Project) : IdeTool {
    override val name = "getWorkspaceFolders"
    override val description = "Get all workspace folders currently open in the IDE"
    override val inputSchema = schema()
    override fun call(args: JsonObject): CompletableFuture<JsonObject> {
        val base = project.basePath ?: return CompletableFuture.completedFuture(Mcp.text("""{"success":false}"""))
        val out = mapOf(
            "success" to true,
            "folders" to listOf(mapOf("name" to project.name, "uri" to "file://$base", "path" to base)),
            "rootPath" to base,
        )
        return CompletableFuture.completedFuture(Mcp.text(gson.toJson(out)))
    }
}

class GetOpenEditors(private val project: Project) : IdeTool {
    override val name = "getOpenEditors"
    override val description = "Get information about currently open editors"
    override val inputSchema = schema()
    override fun call(args: JsonObject): CompletableFuture<JsonObject> {
        val tabs = ReadAction.compute<List<Map<String, Any>>, RuntimeException> {
            val fem = FileEditorManager.getInstance(project)
            val active = fem.selectedFiles.firstOrNull()
            fem.openFiles.map { f ->
                mapOf(
                    "uri" to f.url, "isActive" to (f == active), "label" to f.name,
                    "languageId" to (f.fileType.name.lowercase()),
                    "isDirty" to com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().isFileModified(f),
                )
            }
        }
        return CompletableFuture.completedFuture(Mcp.text(gson.toJson(mapOf("tabs" to tabs))))
    }
}

class GetCurrentSelection(private val project: Project) : IdeTool {
    override val name = "getCurrentSelection"
    override val description = "Get the current text selection in the active editor"
    override val inputSchema = schema()
    override fun call(args: JsonObject): CompletableFuture<JsonObject> {
        val res = ReadAction.compute<Map<String, Any>, RuntimeException> {
            val ed = (FileEditorManager.getInstance(project).selectedEditor as? TextEditor)?.editor
                ?: return@compute mapOf("success" to false, "message" to "No active editor found")
            val m = ed.selectionModel
            val doc = ed.document
            val s = doc.getLineNumber(m.selectionStart); val e = doc.getLineNumber(m.selectionEnd)
            mapOf(
                "success" to true, "text" to (m.selectedText ?: ""),
                "filePath" to (FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path ?: ""),
                "selection" to mapOf(
                    "start" to mapOf("line" to s, "character" to m.selectionStart - doc.getLineStartOffset(s)),
                    "end" to mapOf("line" to e, "character" to m.selectionEnd - doc.getLineStartOffset(e)),
                ),
            )
        }
        return CompletableFuture.completedFuture(Mcp.text(gson.toJson(res)))
    }
}

class OpenFile(private val project: Project) : IdeTool {
    override val name = "openFile"
    override val description = "Open a file in the editor and optionally select a range of text"
    override val inputSchema = schema("filePath" to "string", "preview" to "boolean", required = listOf("filePath"))
    override fun call(args: JsonObject): CompletableFuture<JsonObject> {
        val path = args.get("filePath").asString
        val f = CompletableFuture<JsonObject>()
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path))
            if (vf == null) f.complete(Mcp.text("""{"success":false,"message":"not found"}"""))
            else { OpenFileDescriptor(project, vf).navigate(true); f.complete(Mcp.text("Opened file: $path")) }
            // TODO: startText/endText selection, makeFrontmost=false detailed JSON result
        }
        return f
    }
}

/** TODO: real implementation via DaemonCodeAnalyzerImpl.getHighlights / HighlightInfo; for Rust, rust-analyzer/clippy surface here. */
class GetDiagnostics(private val project: Project) : IdeTool {
    override val name = "getDiagnostics"
    override val description = "Get language diagnostics from the IDE"
    override val inputSchema = schema("uri" to "string")
    override fun call(args: JsonObject) = CompletableFuture.completedFuture(Mcp.text("[]"))
}
