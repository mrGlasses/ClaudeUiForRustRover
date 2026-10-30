package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Translates one `claude -p --output-format stream-json` line into the panel's small message set (webview/src/bridge.ts).
 * The panel never sees raw CLI events: it gets only what it renders, and every text it gets is rendered either
 * sanitized (Markdown) or as plain text. Shapes from STREAM-PROTOCOL.md (CLI 2.1.312). Pure: unit-tested.
 *
 * Phase 7b adds thinking blocks (start / delta / stop) and the to-do list: TodoWrite (whole list) or the task tools
 * TaskCreate / TaskUpdate (one item at a time; the new id arrives in the result's `tool_use_result.task.id`).
 * Task tools are off by default on recent models (code.claude.com/docs/en/agent-sdk/todo-tracking).
 * Still out of scope: subagent output (parent_tool_use_id != null, Phase 11), permission cards (Phase 8).
 */
object StreamEvents {
    private const val SUMMARY_MAX = 200
    private const val PREVIEW_MAX = 600
    private val TODO_STATUSES = setOf("pending", "in_progress", "completed")

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

    /**
     * Token streaming, keyed by message id + content block index: text deltas, and thinking blocks (start, deltas, stop).
     * Thinking text can be empty (the CLI sent `"thinking":""` with only a signature in spike v3); the panel then shows
     * just "Thought for N s". `blockStop` is sent for every block; the panel ignores it for blocks it doesn't track.
     */
    private fun streamEvent(e: JsonObject): List<JsonObject> {
        val ev = e.obj("event") ?: return emptyList()
        val id = e.str("api_message_id") ?: return emptyList()
        val index = ev.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val key = "$id:$index"
        return when (ev.str("type")) {
            "content_block_start" ->
                if (ev.obj("content_block")?.str("type") == "thinking") listOf(msg("thinkingStart") { addProperty("key", key) })
                else emptyList()
            "content_block_stop" -> listOf(msg("blockStop") { addProperty("key", key) })
            "content_block_delta" -> {
                val delta = ev.obj("delta") ?: return emptyList()
                when (delta.str("type")) {
                    "text_delta" -> listOf(msg("textDelta") {
                        addProperty("messageId", id)
                        addProperty("key", key)
                        addProperty("text", delta.str("text") ?: "")
                    })
                    "thinking_delta" -> listOf(msg("thinkingDelta") {
                        addProperty("key", key)
                        addProperty("text", delta.str("thinking") ?: "")
                    })
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    /**
     * Whole assistant message: final text per block (replaces the streamed text) and tool calls.
     * The CLI sends one `assistant` event per content block, each with a one-element `content`, so the index here is
     * always 0 while the stream index counts thinking blocks too. The panel therefore matches a final text to the
     * oldest unfinished streamed block of the same messageId; `key` is only a fallback (v0.14.0 duplicated text).
     */
    private fun assistant(e: JsonObject): List<JsonObject> {
        val m = e.obj("message") ?: return emptyList()
        val id = m.str("id") ?: return emptyList()
        val content = m.get("content") as? JsonArray ?: return emptyList()
        return content.mapIndexedNotNull { index, el ->
            val block = el as? JsonObject ?: return@mapIndexedNotNull null
            when (block.str("type")) {
                "text" -> msg("textFinal") {
                    addProperty("messageId", id)
                    addProperty("key", "$id:final:$index")
                    addProperty("text", block.str("text") ?: "")
                }
                "tool_use" -> when (block.str("name")) {
                    "TodoWrite" -> todos(block.obj("input"))
                    "TaskCreate" -> taskCreate(block)
                    "TaskUpdate" -> taskUpdate(block.obj("input"))
                    "TaskGet", "TaskList" -> null // reading the list back: nothing to show
                    else -> toolUse(block)
                }
                else -> null
            }
        }
    }

    private fun toolUse(block: JsonObject) = msg("toolUse") {
        addProperty("id", block.str("id") ?: "")
        addProperty("name", block.str("name") ?: "tool")
        addProperty("summary", toolSummary(block.obj("input")))
    }

    /** Tool results and the interrupt marker arrive as `user` messages. */
    private fun user(e: JsonObject): List<JsonObject> {
        val content = e.obj("message")?.get("content") as? JsonArray ?: return emptyList()
        return content.mapNotNull { el ->
            val block = el as? JsonObject ?: return@mapNotNull null
            when (block.str("type")) {
                "tool_result" -> {
                    // TaskCreate's result carries the new task id (structured output on the user message).
                    val createdId = e.obj("tool_use_result")?.obj("task")?.str("id")
                    if (createdId != null) msg("taskCreated") {
                        addProperty("toolUseId", block.str("tool_use_id") ?: "")
                        addProperty("taskId", createdId)
                    } else toolResult(block)
                }
                "text" -> {
                    val text = block.str("text") ?: ""
                    if (text.startsWith("[Request interrupted")) msg("notice") { addProperty("text", "Interrupted.") } else null
                }
                else -> null
            }
        }
    }

    private fun toolResult(block: JsonObject) = msg("toolResult") {
        addProperty("toolUseId", block.str("tool_use_id") ?: "")
        addProperty("isError", block.get("is_error")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false)
        addProperty("preview", textOf(block.get("content")).take(PREVIEW_MAX))
    }

    /** TaskCreate input: `subject`, `activeForm` (or `active_form`). The id comes later with `taskCreated`. */
    private fun taskCreate(block: JsonObject): JsonObject = msg("taskCreate") {
        val input = block.obj("input")
        addProperty("toolUseId", block.str("id") ?: "")
        addProperty("subject", (input?.str("subject") ?: "").take(SUMMARY_MAX))
        (input?.str("activeForm") ?: input?.str("active_form"))?.let { addProperty("activeForm", it.take(SUMMARY_MAX)) }
    }

    /** TaskUpdate input: `taskId` (or `id` / `task_id`), optional `status` (incl. `deleted`), `activeForm`, `subject`. */
    private fun taskUpdate(input: JsonObject?): JsonObject? {
        if (input == null) return null
        val id = input.str("taskId") ?: input.str("id") ?: input.str("task_id") ?: return null
        return msg("taskUpdate") {
            addProperty("taskId", id)
            input.str("status")?.takeIf { it in TODO_STATUSES || it == "deleted" }?.let { addProperty("status", it) }
            (input.str("activeForm") ?: input.str("active_form"))?.let { addProperty("activeForm", it.take(SUMMARY_MAX)) }
            input.str("subject")?.let { addProperty("subject", it.take(SUMMARY_MAX)) }
        }
    }

    /** TodoWrite replaces the whole list each time: `todos: [{content, status, activeForm}]`. Unknown statuses become pending. */
    private fun todos(input: JsonObject?): JsonObject = msg("todos") {
        val items = JsonArray()
        (input?.get("todos") as? JsonArray)?.forEach { el ->
            val t = el as? JsonObject ?: return@forEach
            val status = t.str("status")?.takeIf { it in TODO_STATUSES } ?: "pending"
            val text = (if (status == "in_progress") t.str("activeForm") else null) ?: t.str("content") ?: return@forEach
            items.add(JsonObject().apply { addProperty("text", text.take(SUMMARY_MAX)); addProperty("status", status) })
        }
        add("items", items)
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
