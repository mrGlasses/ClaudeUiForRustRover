package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Phase 11b: the agent map. Subagents, background shell commands and monitors are "tasks" in the CLI's stream
 * (schema of claude 2.1.316):
 *
 *  - `system/task_started`   {task_id, tool_use_id?, description, subagent_type?, task_type?, parent_task_id?, is_backgrounded?,
 *                             prompt?, skip_transcript?, ambient?}
 *  - `system/task_progress`  {task_id, usage{total_tokens, tool_uses, duration_ms}, last_tool_name?, summary?}
 *  - `system/task_updated`   {task_id, patch{status?, description?, error?, is_backgrounded?}}
 *  - `system/task_notification` {task_id, status: completed|failed|stopped, summary, usage?}
 *  - `system/background_tasks_changed` {tasks: [...]}: every live background task (replace semantics)
 *  - subagent traffic: ordinary `assistant` / `user` events with `parent_tool_use_id` = the Agent tool call's id.
 *
 * Control requests the panel may send: `stop_task {task_id}`, `background_tasks {tool_use_id}` (Ctrl+B for one call),
 * `get_task_output {task_id}` (last 8 KiB of a shell or monitor task: {output, total_bytes, truncated}).
 * Pure: unit-tested. Texts are bounded; the page renders all of them as plain text except agent text (sanitized Markdown).
 */
object AgentTasks {
    private const val TEXT_MAX = 4000
    private const val PROMPT_MAX = 2000
    /** Tool calls that can be sent to the background while they run. */
    val BACKGROUNDABLE = setOf("Bash", "PowerShell", "Agent", "Task")
    private val STATUSES = setOf("pending", "running", "completed", "failed", "killed", "paused", "stopped")

    /** "agent", "shell", "monitor" or "other": what the map shows and which actions it offers (output: shell, monitor). */
    fun kind(taskType: String?, subagentType: String?): String {
        val t = taskType?.lowercase() ?: ""
        return when {
            "agent" in t || (t.isEmpty() && subagentType != null) -> "agent"
            "bash" in t || "shell" in t -> "shell"
            "monitor" in t -> "monitor"
            else -> "other"
        }
    }

    fun hasOutput(kind: String) = kind == "shell" || kind == "monitor"

    /** `system` events with a task subtype; null for any other subtype. */
    fun system(e: JsonObject): List<JsonObject>? = when (e.str("subtype")) {
        "task_started" -> {
            val id = e.str("task_id")
            if (id == null) emptyList() else listOf(msg("agentTaskStarted") {
                addProperty("taskId", id)
                e.str("tool_use_id")?.let { addProperty("toolUseId", it) }
                addProperty("description", (e.str("description") ?: "").take(300))
                addProperty("kind", kind(e.str("task_type"), e.str("subagent_type")))
                e.str("subagent_type")?.let { addProperty("subagentType", it.take(80)) }
                e.str("parent_task_id")?.let { addProperty("parentTaskId", it) }
                addProperty("background", e.isTrue("is_backgrounded"))
                addProperty("hidden", e.isTrue("skip_transcript") || e.isTrue("ambient"))
                e.str("prompt")?.let { addProperty("prompt", it.take(PROMPT_MAX)) }
            })
        }
        "task_progress" -> e.str("task_id")?.let { id ->
            listOf(msg("agentTaskProgress") {
                addProperty("taskId", id)
                usage(e.obj("usage"))
                e.str("last_tool_name")?.let { addProperty("lastTool", it.take(80)) }
                e.str("summary")?.let { addProperty("summary", it.take(300)) }
            })
        } ?: emptyList()
        "task_updated" -> e.str("task_id")?.let { id ->
            val patch = e.obj("patch") ?: JsonObject()
            listOf(msg("agentTaskUpdated") {
                addProperty("taskId", id)
                patch.str("status")?.takeIf { it in STATUSES }?.let { addProperty("status", it) }
                patch.str("description")?.let { addProperty("description", it.take(300)) }
                patch.str("error")?.let { addProperty("error", it.take(500)) }
                patch.get("is_backgrounded")?.takeIf { it.isJsonPrimitive }?.let { addProperty("background", it.asBoolean) }
            })
        } ?: emptyList()
        "task_notification" -> e.str("task_id")?.let { id ->
            listOf(msg("agentTaskEnded") {
                addProperty("taskId", id)
                addProperty("status", e.str("status")?.takeIf { it in STATUSES } ?: "completed")
                addProperty("summary", (e.str("summary") ?: "").take(TEXT_MAX))
                usage(e.obj("usage"))
            })
        } ?: emptyList()
        "background_tasks_changed" -> listOf(msg("agentTasksLive") {
            add("tasks", JsonArray().apply {
                (e.get("tasks") as? JsonArray)?.take(200)?.forEach { t ->
                    val o = t as? JsonObject ?: return@forEach
                    val id = o.str("task_id") ?: return@forEach
                    add(JsonObject().apply {
                        addProperty("taskId", id)
                        addProperty("description", (o.str("description") ?: "").take(300))
                        addProperty("kind", kind(o.str("task_type"), o.str("subagent_type")))
                        o.str("subagent_type")?.let { addProperty("subagentType", it.take(80)) }
                        o.str("parent_task_id")?.let { addProperty("parentTaskId", it) }
                        addProperty("hidden", o.isTrue("ambient"))
                    })
                }
            })
        })
        else -> null
    }

    /** A subagent's own messages, for its transcript in the map: its text and the tools it calls. */
    fun subagent(e: JsonObject): List<JsonObject> {
        val parent = e.str("parent_tool_use_id") ?: return emptyList()
        if (e.str("type") != "assistant") return emptyList() // stream deltas and tool results stay out of the map
        val content = e.obj("message")?.get("content") as? JsonArray ?: return emptyList()
        return content.mapNotNull { el ->
            val b = el as? JsonObject ?: return@mapNotNull null
            when (b.str("type")) {
                "text" -> (b.str("text") ?: "").takeIf { it.isNotBlank() }?.let { t ->
                    msg("agentActivity") { addProperty("toolUseId", parent); addProperty("kind", "text"); addProperty("text", t.take(TEXT_MAX)) }
                }
                "tool_use" -> msg("agentActivity") {
                    addProperty("toolUseId", parent)
                    addProperty("kind", "tool")
                    addProperty("text", "${b.str("name") ?: "tool"}: ${StreamEvents.toolSummary(b.obj("input"))}".take(300))
                }
                else -> null
            }
        }
    }

    /** `get_task_output`'s answer as plain text: terminal escape sequences removed, carriage-return redraws collapsed. */
    fun output(payload: JsonObject?): JsonObject = JsonObject().apply {
        addProperty("output", plain(payload?.str("output") ?: ""))
        addProperty("truncated", payload?.isTrue("truncated") ?: false)
        payload?.get("total_bytes")?.takeIf { it.isJsonPrimitive }?.let { addProperty("totalBytes", runCatching { it.asLong }.getOrDefault(0L)) }
    }

    private val ESCAPES = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]")

    fun plain(s: String): String = s.replace(ESCAPES, "").replace("\r\n", "\n").split('\n') // not lines(): it splits at a lone \r too
        .joinToString("\n") { line -> line.substringAfterLast('\r') } // a progress bar redraws its line with \r
        .filter { it == '\n' || it == '\t' || it >= ' ' }

    private fun JsonObject.usage(u: JsonObject?) {
        if (u == null) return
        u.get("total_tokens")?.takeIf { it.isJsonPrimitive }?.let { addProperty("tokens", runCatching { it.asLong }.getOrDefault(0L)) }
        u.get("tool_uses")?.takeIf { it.isJsonPrimitive }?.let { addProperty("toolUses", runCatching { it.asInt }.getOrDefault(0)) }
        u.get("duration_ms")?.takeIf { it.isJsonPrimitive }?.let { addProperty("durationMs", runCatching { it.asLong }.getOrDefault(0L)) }
    }

    private fun msg(type: String, fill: JsonObject.() -> Unit) = JsonObject().apply { addProperty("type", type); fill() }
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.isTrue(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
}
