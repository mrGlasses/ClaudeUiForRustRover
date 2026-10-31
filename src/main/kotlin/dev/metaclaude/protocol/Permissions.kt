package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Permission requests (`can_use_tool` control requests) for the chat panel: what the card shows, and the answer the CLI gets.
 * Shapes: STREAM-PROTOCOL.md and code.claude.com/docs/en/agent-sdk/user-input. Pure: unit-tested.
 *
 *  - allow:  {"behavior":"allow","updatedInput":<input>[,"updatedPermissions":[<suggestion>]]} (CLI 2.1 needs updatedInput)
 *  - deny:   {"behavior":"deny","message":"<why or what to do instead>"}
 *  - AskUserQuestion: allow with updatedInput {questions, answers: {<question text>: <label or labels or own text>}}
 *  - ExitPlanMode: allow = approve the plan, deny with feedback = keep planning.
 */
object Permissions {
    /** Where an `addRules` suggestion can be saved; the card lets the user change it (VS Code's "save to"). */
    val DESTINATIONS = listOf("session", "localSettings", "projectSettings", "userSettings")
    private val PERSISTENT = setOf("localSettings", "projectSettings", "userSettings")

    /** Modes the panel may ask for. Bypass is added only when the user allowed it in settings. */
    val MODES = linkedMapOf(
        "default" to "Ask before edits",
        "acceptEdits" to "Edit automatically",
        "plan" to "Plan mode",
        "auto" to "Auto mode",
    )
    const val BYPASS = "bypassPermissions"
    const val BYPASS_LABEL = "Bypass permissions"

    private const val PREVIEW_MAX = 4000
    private const val PLAN_MAX = 100_000
    const val MESSAGE_MAX = 10_000

    fun destinationLabel(d: String?): String = when (d) {
        "session" -> "this session"
        "localSettings" -> "this project, just you (.claude/settings.local.json)"
        "projectSettings" -> "this project, shared (.claude/settings.json)"
        "userSettings" -> "all your projects (~/.claude/settings.json)"
        "cliArg" -> "this session"
        else -> d ?: "this session"
    }

    fun isPersistent(suggestion: JsonObject, destination: String? = null): Boolean =
        (destination ?: suggestion.str("destination")) in PERSISTENT

    /** One line for a suggestion button ("Yes, and ..."). */
    fun suggestionLabel(s: JsonObject): String = when (s.str("type")) {
        "setMode" -> "Yes, and switch to ${MODES[s.str("mode")] ?: s.str("mode")}"
        "addDirectories" -> {
            val dirs = (s.get("directories") as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } ?: emptyList()
            "Yes, and allow access to ${dirs.joinToString(", ").ifEmpty { "this folder" }}"
        }
        "addRules", "replaceRules" -> {
            val rules = (s.get("rules") as? JsonArray)?.mapNotNull { r ->
                val o = r as? JsonObject ?: return@mapNotNull null
                val tool = o.str("toolName") ?: return@mapNotNull null
                o.str("ruleContent")?.let { "$tool($it)" } ?: tool
            } ?: emptyList()
            val verb = if (s.str("behavior") == "deny") "No, and always deny" else "Yes, and don't ask again for"
            "$verb ${rules.joinToString(", ").ifEmpty { "this" }}"
        }
        else -> "Yes, and apply: ${s.str("type") ?: "rule"}"
    }

    /** The panel's card for one request. [id] is the CLI's request id (a UUID). */
    fun card(id: String, request: JsonObject): JsonObject {
        val tool = request.str("tool_name") ?: "tool"
        val input = request.get("input") as? JsonObject ?: JsonObject()
        return JsonObject().apply {
            addProperty("type", "permission")
            addProperty("requestId", id)
            addProperty("toolName", tool)
            addProperty("displayName", request.str("display_name") ?: tool)
            addProperty("kind", when (tool) { "AskUserQuestion" -> "question"; "ExitPlanMode" -> "plan"; else -> "tool" })
            request.str("description")?.let { addProperty("description", it.take(500)) }
            request.str("decision_reason")?.let { addProperty("reason", it.take(500)) }
            when (tool) {
                "AskUserQuestion" -> add("questions", questions(input))
                "ExitPlanMode" -> addProperty("plan", (input.str("plan") ?: "").take(PLAN_MAX))
                else -> addProperty("preview", preview(tool, input))
            }
            val suggestions = JsonArray()
            (request.get("permission_suggestions") as? JsonArray)?.forEachIndexed { i, el ->
                val s = el as? JsonObject ?: return@forEachIndexed
                suggestions.add(JsonObject().apply {
                    addProperty("index", i)
                    addProperty("label", suggestionLabel(s).take(300))
                    addProperty("destination", s.str("destination") ?: "session")
                    addProperty("canChangeDestination", s.str("type") == "addRules")
                })
            }
            add("suggestions", suggestions)
        }
    }

    /** What the tool will do, as plain text for the card. Edit shows old/new; never more than PREVIEW_MAX chars. */
    fun preview(tool: String, input: JsonObject): String {
        val text = when (tool) {
            "Bash", "PowerShell" -> listOfNotNull(input.str("description"), input.str("command")?.let { "$ $it" }).joinToString("\n")
            "Edit" -> buildString {
                append(input.str("file_path") ?: "")
                input.str("old_string")?.lines()?.forEach { append("\n- ").append(it) }
                input.str("new_string")?.lines()?.forEach { append("\n+ ").append(it) }
            }
            "MultiEdit" -> (input.str("file_path") ?: "") + "\n(${(input.get("edits") as? JsonArray)?.size() ?: 0} edits)"
            "Write" -> (input.str("file_path") ?: "") + "\n" + (input.str("content") ?: "")
            "WebFetch" -> listOfNotNull(input.str("url"), input.str("prompt")).joinToString("\n")
            else -> input.toString()
        }
        return if (text.length > PREVIEW_MAX) text.take(PREVIEW_MAX) + "\n…" else text
    }

    /** A clean copy of AskUserQuestion's questions: text, header, multiSelect, options (label, description). */
    private fun questions(input: JsonObject): JsonArray = JsonArray().also { out ->
        (input.get("questions") as? JsonArray)?.take(4)?.forEach { el ->
            val q = el as? JsonObject ?: return@forEach
            out.add(JsonObject().apply {
                addProperty("question", (q.str("question") ?: "").take(1000))
                addProperty("header", (q.str("header") ?: "").take(40))
                addProperty("multiSelect", q.get("multiSelect")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false)
                add("options", JsonArray().also { opts ->
                    (q.get("options") as? JsonArray)?.take(8)?.forEach { o ->
                        val obj = o as? JsonObject ?: return@forEach
                        opts.add(JsonObject().apply {
                            addProperty("label", (obj.str("label") ?: "").take(200))
                            addProperty("description", (obj.str("description") ?: "").take(500))
                        })
                    }
                })
            })
        }
    }

    fun allow(input: JsonObject, suggestion: JsonObject? = null): JsonObject = JsonObject().apply {
        addProperty("behavior", "allow")
        add("updatedInput", input)
        if (suggestion != null) add("updatedPermissions", JsonArray().apply { add(suggestion) })
    }

    fun deny(message: String): JsonObject = JsonObject().apply {
        addProperty("behavior", "deny")
        addProperty("message", message)
    }

    /**
     * AskUserQuestion answers from the panel, checked against the real questions: keys must be question texts,
     * values a string or a list of strings (labels or the user's own text). Returns null when nothing valid remains.
     */
    fun answeredInput(input: JsonObject, answers: JsonObject?, response: String?): JsonObject? {
        val known = (input.get("questions") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("question") }?.toSet() ?: emptySet()
        val clean = JsonObject()
        answers?.entrySet()?.forEach { (q, v) ->
            if (q !in known) return@forEach
            val value: JsonElement? = when {
                v.isJsonPrimitive -> v.asString.trim().take(MESSAGE_MAX).takeIf { it.isNotEmpty() }?.let { com.google.gson.JsonPrimitive(it) }
                v is JsonArray -> JsonArray().also { a ->
                    v.take(8).forEach { x -> if (x.isJsonPrimitive) x.asString.trim().take(1000).takeIf { it.isNotEmpty() }?.let(a::add) }
                }.takeIf { it.size() > 0 }
                else -> null
            }
            if (value != null) clean.add(q, value)
        }
        val reply = response?.trim()?.take(MESSAGE_MAX)?.takeIf { it.isNotEmpty() }
        if (clean.size() == 0 && reply == null) return null
        return JsonObject().apply {
            add("questions", input.get("questions") ?: JsonArray())
            add("answers", clean)
            if (reply != null) addProperty("response", reply)
        }
    }

    /** A copy of [s] saved to [destination] instead (only for addRules, only to a known destination). */
    fun withDestination(s: JsonObject, destination: String?): JsonObject {
        if (destination == null || destination !in DESTINATIONS || s.str("type") != "addRules") return s
        return s.deepCopy().apply { addProperty("destination", destination) }
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
