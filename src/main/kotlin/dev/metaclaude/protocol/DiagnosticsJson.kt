package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * getDiagnostics result, shaped like the VS Code extension's (claudecode.nvim PROTOCOL.md):
 * [{"uri": "...", "diagnostics": [{"message", "severity", "range": {"start", "end"}, "source"}]}]
 * Lines and characters are 0-based. Severity: "Error" | "Warning" | "Information".
 */
object DiagnosticsJson {
    data class Diagnostic(
        val message: String,
        val severity: String,
        val startLine: Int, val startChar: Int,
        val endLine: Int, val endChar: Int,
        val source: String,
    )

    data class FileDiagnostics(val uri: String, val diagnostics: List<Diagnostic>)

    private fun pos(line: Int, character: Int) = JsonObject().apply {
        addProperty("line", line); addProperty("character", character)
    }

    fun toJson(files: List<FileDiagnostics>): String = JsonArray().apply {
        files.forEach { f ->
            add(JsonObject().apply {
                addProperty("uri", f.uri)
                add("diagnostics", JsonArray().apply {
                    f.diagnostics.forEach { d ->
                        add(JsonObject().apply {
                            addProperty("message", d.message)
                            addProperty("severity", d.severity)
                            add("range", JsonObject().apply {
                                add("start", pos(d.startLine, d.startChar))
                                add("end", pos(d.endLine, d.endChar))
                            })
                            addProperty("source", d.source)
                        })
                    }
                })
            })
        }
    }.toString()
}
