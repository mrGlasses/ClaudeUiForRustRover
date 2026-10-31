package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Phase 8b: what an Edit / MultiEdit / Write permission request would turn a file into, for the IDE diff viewer, and the
 * tool input to send back when the user changed the proposed side. Pure: unit-tested.
 *
 * Text is compared with \n line ends (IntelliJ documents use \n only); the input sent back keeps the file's own line ends
 * (CRLF files stay CRLF), because the CLI's Edit tool matches `old_string` against the bytes on disk.
 */
object EditProposal {
    val TOOLS = setOf("Edit", "MultiEdit", "Write")

    fun filePath(input: JsonObject): String? = input.str("file_path")

    private fun nl(s: String) = s.replace("\r\n", "\n").replace('\r', '\n')

    /** The proposed new text (\n line ends), or null when the edit doesn't apply to [current] (old text not found). */
    fun proposed(tool: String, input: JsonObject, current: String): String? {
        val text = nl(current)
        return when (tool) {
            "Write" -> nl(input.str("content") ?: return null)
            "Edit" -> applyEdit(text, input)
            "MultiEdit" -> (input.get("edits") as? JsonArray)?.fold(text as String?) { acc, e ->
                acc?.let { a -> (e as? JsonObject)?.let { applyEdit(a, it) } }
            }
            else -> null
        }
    }

    private fun applyEdit(text: String, edit: JsonObject): String? {
        val old = nl(edit.str("old_string") ?: return null)
        val new = nl(edit.str("new_string") ?: "")
        if (old.isEmpty()) return if (text.isEmpty()) new else null
        if (!text.contains(old)) return null
        val all = edit.get("replace_all")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        return if (all) text.replace(old, new) else text.replaceFirst(old, new)
    }

    /**
     * Input for an allow answer after the user edited the proposal in the diff: Write gets the new content; Edit and MultiEdit
     * become one replacement of the whole file on disk ([disk], exact bytes) by the edited text, in the file's line ends.
     */
    fun editedInput(tool: String, input: JsonObject, disk: String, edited: String): JsonObject {
        val crlf = disk.contains("\r\n")
        val text = nl(edited).let { if (crlf) it.replace("\n", "\r\n") else it }
        return input.deepCopy().apply {
            when (tool) {
                "Write" -> addProperty("content", text)
                "Edit" -> {
                    addProperty("old_string", disk)
                    addProperty("new_string", text)
                    addProperty("replace_all", false)
                }
                "MultiEdit" -> add("edits", JsonArray().apply {
                    add(JsonObject().apply { addProperty("old_string", disk); addProperty("new_string", text) })
                })
            }
        }
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
