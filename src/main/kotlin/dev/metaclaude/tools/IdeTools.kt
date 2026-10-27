package dev.metaclaude.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.metaclaude.protocol.IdeTool
import dev.metaclaude.protocol.Mcp
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

// SKETCH — written against my memory of the IntelliJ Platform API, NOT compiled. Expect signature fixes.
// Tool names/shapes follow coder/claudecode.nvim PROTOCOL.md (reverse-engineered from the VS Code extension).

private val gson = Gson()
private fun schema(vararg props: Pair<String, String>, required: List<String> = emptyList()) = JsonObject().apply {
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

/**
 * The blocking tool. The reply to tools/call is withheld until the user decides.
 * Results (per PROTOCOL.md): "FILE_SAVED" | "DIFF_REJECTED"; VS-port notes also mention "TAB_CLOSED".
 */
class DiffCoordinator(private val project: Project) : IdeTool {
    override val name = "openDiff"
    override val description = "Open a diff for the file and wait for the user to accept or reject (blocking)"
    override val inputSchema = schema(
        "old_file_path" to "string", "new_file_path" to "string",
        "new_file_contents" to "string", "tab_name" to "string",
        required = listOf("old_file_path", "new_file_path", "new_file_contents", "tab_name"),
    )

    private val pending = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()

    override fun call(args: JsonObject): CompletableFuture<JsonObject> {
        val tab = args.get("tab_name").asString
        val oldPath = Path.of(args.get("old_file_path").asString)
        val newPath = Path.of(args.get("new_file_path").asString)
        val newText = args.get("new_file_contents").asString

        val future = CompletableFuture<JsonObject>()
        pending[tab] = future
        future.whenComplete { _, _ -> pending.remove(tab) }

        ApplicationManager.getApplication().invokeLater {
            val factory = DiffContentFactory.getInstance()
            val left = if (Files.exists(oldPath)) factory.create(project, LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(oldPath)!!) else factory.createEmpty()
            val right = factory.create(project, newText)
            val request = SimpleDiffRequest(tab, left, right, "Current", "Proposed")
            // TODO(UI): add Accept / Reject context actions (DiffUserDataKeys.CONTEXT_ACTIONS) that call
            //   accept(newPath, newText, future) / reject(future); and detect "window closed with no decision"
            //   (DiffRequest.onAssigned(false)) -> complete with TAB_CLOSED.
            DiffManager.getInstance().showDiff(project, request)
        }
        return future
    }

    private fun accept(newPath: Path, newText: String, f: CompletableFuture<JsonObject>) {
        // SPIKE: if the user edits the right-hand side, write THAT text, not newText.
        ApplicationManager.getApplication().runWriteAction { Files.writeString(newPath, newText) }
        f.complete(Mcp.text("FILE_SAVED"))
    }
    private fun reject(f: CompletableFuture<JsonObject>) = f.complete(Mcp.text("DIFF_REJECTED"))

    /** closeAllDiffTabs / close_tab must also resolve any pending future, or the CLI waits forever. */
    fun closeAll(): Int { val n = pending.size; pending.values.forEach { it.complete(Mcp.text("TAB_CLOSED")) }; return n }
}
