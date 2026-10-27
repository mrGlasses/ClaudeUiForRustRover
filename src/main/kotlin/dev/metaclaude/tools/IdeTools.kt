package dev.metaclaude.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.metaclaude.protocol.CliPaths
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

/** Optional string argument (absent or JSON null → null). */
internal fun JsonObject.opt(key: String): String? = get(key)?.takeIf { !it.isJsonNull }?.asString
internal fun JsonObject.optBool(key: String, default: Boolean): Boolean = get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: default

class GetWorkspaceFolders(private val project: Project) : IdeTool {
    override val name = "getWorkspaceFolders"
    override val description = "Get all workspace folders currently open in the IDE"
    override val inputSchema = schema()
    override fun call(args: JsonObject): CompletableFuture<JsonObject> {
        val base = project.basePath ?: return CompletableFuture.completedFuture(Mcp.text("""{"success":false}"""))
        val path = CliPaths.forCli(base)
        val out = mapOf(
            "success" to true,
            "folders" to listOf(mapOf("name" to project.name, "uri" to CliPaths.fileUrl(path), "path" to path)),
            "rootPath" to path,
        )
        return CompletableFuture.completedFuture(Mcp.text(gson.toJson(out)))
    }
}

class GetOpenEditors(private val project: Project) : IdeTool {
    override val name = "getOpenEditors"
    override val description = "Get information about currently open editors"
    override val inputSchema = schema()
    override fun call(args: JsonObject): CompletableFuture<JsonObject> = onEdt {
        val fem = FileEditorManager.getInstance(project)
        val fdm = FileDocumentManager.getInstance()
        val active = fem.selectedFiles.firstOrNull()
        val tabs = fem.openFiles.filter { it.isInLocalFileSystem }.map { f ->
            mapOf(
                "uri" to CliPaths.fileUrl(CliPaths.forCli(f.path)), "isActive" to (f == active), "label" to f.name,
                "languageId" to f.fileType.name.lowercase(), "isDirty" to fdm.isFileModified(f),
            )
        }
        Mcp.text(gson.toJson(mapOf("tabs" to tabs)))
    }
}

class GetCurrentSelection(private val tracker: SelectionTracker) : IdeTool {
    override val name = "getCurrentSelection"
    override val description = "Get the current text selection in the active editor"
    override val inputSchema = schema()
    override fun call(args: JsonObject): CompletableFuture<JsonObject> = onEdt {
        val snap = tracker.current()
            ?: return@onEdt Mcp.text(gson.toJson(mapOf("success" to false, "message" to "No active editor found")))
        snap.addProperty("success", true)
        Mcp.text(snap.toString())
    }
}

class GetLatestSelection(private val tracker: SelectionTracker) : IdeTool {
    override val name = "getLatestSelection"
    override val description = "Get the most recent text selection (even if not in the active editor)"
    override val inputSchema = schema()
    override fun call(args: JsonObject): CompletableFuture<JsonObject> = onEdt {
        val snap = tracker.latest?.deepCopy()
            ?: return@onEdt Mcp.text(gson.toJson(mapOf("success" to false, "message" to "No selection available")))
        snap.addProperty("success", true)
        Mcp.text(snap.toString())
    }
}

class OpenFile(private val project: Project) : IdeTool {
    override val name = "openFile"
    override val description = "Open a file in the editor and optionally select a range of text"
    override val inputSchema = schema(
        "filePath" to "string", "preview" to "boolean", "startText" to "string", "endText" to "string",
        "selectToEndOfLine" to "boolean", "makeFrontmost" to "boolean",
        required = listOf("filePath"),
    )

    override fun call(args: JsonObject): CompletableFuture<JsonObject> {
        val path = args.get("filePath").asString
        val startText = args.opt("startText")
        val endText = args.opt("endText")
        val toLineEnd = args.optBool("selectToEndOfLine", false)
        val frontmost = args.optBool("makeFrontmost", true)
        return onEdt {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path))
                ?: return@onEdt Mcp.text(gson.toJson(mapOf("success" to false, "message" to "File not found: $path")))
            val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vf), frontmost)
            if (editor != null && !startText.isNullOrEmpty()) {
                val doc = editor.document
                val text = doc.text
                val s = text.indexOf(startText)
                if (s >= 0) {
                    var e = s + startText.length
                    if (!endText.isNullOrEmpty()) {
                        val i = text.indexOf(endText, e).takeIf { it >= 0 } ?: text.indexOf(endText, s)
                        if (i >= 0) e = i + endText.length
                    }
                    if (toLineEnd) e = doc.getLineEndOffset(doc.getLineNumber(e))
                    editor.selectionModel.setSelection(s, e)
                    editor.caretModel.moveToOffset(s)
                    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                }
            }
            if (frontmost) Mcp.text("Opened file: $path")
            else Mcp.text(gson.toJson(mapOf(
                "success" to true, "filePath" to CliPaths.forCli(vf.path),
                "languageId" to vf.fileType.name.lowercase(),
                "lineCount" to (editor?.document?.lineCount ?: 0),
            )))
        }
    }
}

/** TODO: real implementation via DaemonCodeAnalyzerImpl.getHighlights / HighlightInfo; for Rust, rust-analyzer/clippy surface here. */
class GetDiagnostics(private val project: Project) : IdeTool {
    override val name = "getDiagnostics"
    override val description = "Get language diagnostics from the IDE"
    override val inputSchema = schema("uri" to "string")
    override fun call(args: JsonObject) = CompletableFuture.completedFuture(Mcp.text("[]"))
}
