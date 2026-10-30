package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Translates one `claude -p --output-format stream-json` line into the panel's small message set (webview/src/bridge.ts).
 * The panel never sees raw CLI events: it gets only what it renders, and every text it gets is rendered either
 * sanitized (Markdown) or as plain text. Shapes from STREAM-PROTOCOL.md (CLI 2.1.312). Pure: unit-tested.
 *
 * Out of scope for Phase 7a: thinking blocks, subagent output (parent_tool_use_id != null), permission cards.
 */
object StreamEvents {
    private const val SUMMARY_MAX = 200
    private const val PREVIEW_MAX = 600

    fun toPanel(event: JsonObject): List<JsonObject> {
        // Subagent traffic is shown in Phase 11 (agent map); the main transcript skips it.
        if (event.str("parent_tool_use_id") != null) return emptyList()
        return when (event.str("type")) {
            "system" -> system(event)
            "stream_event" -> streamEvent(event)
            "assistant" -> assistant(event)
            "user" -> user(event)
            "result" -> listOf(msg("turnEnd") {
                addProperty("stopReason", event.str("stop_reason") ?: "")
                event.get("total_cost_usd")?.takeIf { it.isJsonPrimitive }?.let { addProperty("costUsd", it.asDouble) }
                event.get("duration_ms")?.takeIf { it.isJsonPrimitive }?.let { addProperty("durationMs", it.asLong) }
                addProperty("isError", event.get("is_error")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false)
            })
            else -> emptyList()
        }
    }

    private fun system(e: JsonObject): List<JsonObject> = when (e.str("subtype")) {
        "init" -> listOf(msg("sessionInfo") {
            addProperty("model", e.str("model") ?: "")
            addProperty("permissionMode", e.str("permissionMode") ?: "")
        })
        "status" -> if (e.str("status") == "requesting") listOf(msg("busy") {}) else emptyList()
        else -> emptyList()
    }

    /** Token streaming: only text deltas, keyed by message id + content block index. */
    private fun streamEvent(e: JsonObject): List<JsonObject> {
        val ev = e.obj("event") ?: return emptyList()
        if (ev.str("type") != "content_block_delta") return emptyList()
        val delta = ev.obj("delta") ?: return emptyList()
        if (delta.str("type") != "text_delta") return emptyList()
        val id = e.str("api_message_id") ?: return emptyList()
        val index = ev.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        return listOf(msg("textDelta") {
            addProperty("key", "$id:$index")
            addProperty("text", delta.str("text") ?: "")
        })
    }

    /** Whole assistant message: final text per block (replaces the streamed text) and tool calls. */
    private fun assistant(e: JsonObject): List<JsonObject> {
        val m = e.obj("message") ?: return emptyList()
        val id = m.str("id") ?: return emptyList()
        val content = m.get("content") as? JsonArray ?: return emptyList()
        return content.mapIndexedNotNull { index, el ->
            val block = el as? JsonObject ?: return@mapIndexedNotNull null
            when (block.str("type")) {
                "text" -> msg("textFinal") {
                    addProperty("key", "$id:$index")
                    addProperty("text", block.str("text") ?: "")
                }
                "tool_use" -> msg("toolUse") {
                    addProperty("id", block.str("id") ?: "")
                    addProperty("name", block.str("name") ?: "tool")
                    addProperty("summary", toolSummary(block.obj("input")))
                }
                else -> null
            }
        }
    }

    /** Tool results and the interrupt marker arrive as `user` messages. */
    private fun user(e: JsonObject): List<JsonObject> {
        val content = e.obj("message")?.get("content") as? JsonArray ?: return emptyList()
        return content.mapNotNull { el ->
            val block = el as? JsonObject ?: return@mapNotNull null
            when (block.str("type")) {
                "tool_result" -> msg("toolResult") {
                    addProperty("toolUseId", block.str("tool_use_id") ?: "")
                    addProperty("isError", block.get("is_error")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false)
                    addProperty("preview", textOf(block.get("content")).take(PREVIEW_MAX))
                }
                "text" -> {
                    val text = block.str("text") ?: ""
                    if (text.startsWith("[Request interrupted")) msg("notice") { addProperty("text", "Interrupted.") } else null
                }
                else -> null
            }
        }
    }

    /** One line saying what the tool works on: file, command, pattern or URL. Never the whole input. */
    fun toolSummary(input: JsonObject?): String {
        if (input == null) return ""
        val key = listOf("file_path", "command", "pattern", "path", "url", "query", "description", "notebook_path")
            .firstOrNull { input.str(it) != null }
        val value = key?.let { input.str(it) } ?: return ""
        val oneLine = value.lineSequence().first()
        return if (oneLine.length > SUMMARY_MAX) oneLine.take(SUMMARY_MAX) + "…" else oneLine
    }

    private fun textOf(content: JsonElement?): String = when {
        content == null || content.isJsonNull -> ""
        content.isJsonPrimitive -> content.asString
        content is JsonArray -> content.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n")
        else -> ""
    }

    private fun msg(type: String, fill: JsonObject.() -> Unit) = JsonObject().apply { addProperty("type", type); fill() }
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
}
