package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Phase 10: Claude Code's own session files, `~/.claude/projects/<project dir>/<session id>.jsonl` (one JSON object per line:
 * `user` / `assistant` / `system` entries with `uuid`, `parentUuid`, `sessionId`, `timestamp`, `cwd`, plus metadata lines such
 * as `summary` and `custom-title`). Read-only: the plugin never writes them. Pure: unit-tested.
 */
object SessionFiles {
    private val ID = Regex("^[0-9a-fA-F-]{8,64}$")
    private val TITLE_KEYS = listOf("customTitle", "title", "aiTitle", "summary")
    private const val TITLE_MAX = 120

    fun isSessionId(s: String) = ID.matches(s)

    /** The folder name Claude Code uses for a project: every character except letters and digits becomes "-". */
    fun projectDirName(projectPath: String) = projectPath.replace(Regex("[^A-Za-z0-9]"), "-")

    data class Summary(val id: String, val title: String, val modified: Long, val prompts: Int, val cwd: String?)

    /** Title (your own, else Claude's, else the first prompt), prompt count and the folder the session ran in. */
    fun summarize(id: String, lines: Sequence<String>, modified: Long): Summary? {
        var custom: String? = null
        var generated: String? = null
        var first: String? = null
        var prompts = 0
        var cwd: String? = null
        for (line in lines) {
            val o = parse(line) ?: continue
            if (cwd == null) cwd = o.str("cwd")
            when (val type = o.str("type")) {
                "custom-title" -> custom = titleOf(o) ?: custom
                "summary", "ai-title" -> generated = titleOf(o) ?: generated
                "user" -> {
                    if (o.isTrue("isMeta") || o.isTrue("isSidechain")) continue
                    val p = promptText(o.obj("message")?.get("content")) ?: continue
                    prompts++
                    if (first == null) first = p
                }
                else -> if (type?.endsWith("title") == true) generated = titleOf(o) ?: generated
            }
        }
        if (prompts == 0) return null // nothing you'd want to resume
        val title = (custom ?: generated ?: first ?: id).lineSequence().first().trim().let {
            if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it
        }
        return Summary(id, title, modified, prompts, cwd)
    }

    /** What you typed: plain text, or the text blocks of a turn (context blocks and tool results left out). */
    fun promptText(content: JsonElement?): String? {
        val parts: List<String> = when {
            content == null || content.isJsonNull -> emptyList()
            content.isJsonPrimitive -> listOf(content.asString)
            content is JsonArray -> {
                if (content.any { (it as? JsonObject)?.str("type") == "tool_result" }) return null
                content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }
            }
            else -> emptyList()
        }
        val text = parts.filterNot { isInjected(it) }.joinToString("\n").trim()
        return text.ifEmpty { null }
    }

    /** Blocks the IDE or the CLI adds around a prompt (ours from Phase 9 included), not typed by you. */
    private fun isInjected(t: String): Boolean {
        val s = t.trimStart()
        return listOf("<ide_", "<terminal_output", "<system-reminder>", "<command-", "<local-command-", "Caveat:").any { s.startsWith(it) }
    }

    /**
     * The conversation as panel messages (webview/src/bridge.ts), to show a resumed session: your prompts (`historyUser`),
     * Claude's text and tool cards via [StreamEvents]. Final-text keys are made unique, since a replay has no stream deltas.
     */
    fun transcript(lines: Sequence<String>): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var n = 0
        for (line in lines) {
            val o = parse(line) ?: continue
            if (o.isTrue("isSidechain") || o.isTrue("isMeta")) continue
            when (o.str("type")) {
                "user" -> {
                    val prompt = promptText(o.obj("message")?.get("content"))
                    if (prompt != null) {
                        out.add(JsonObject().apply {
                            addProperty("type", "historyUser")
                            addProperty("text", prompt)
                            o.str("timestamp")?.let { addProperty("timestamp", it) }
                        })
                    } else out.addAll(StreamEvents.toPanel(o))
                }
                "assistant" -> StreamEvents.toPanel(o).forEach { m ->
                    if (m.str("type") == "textFinal") m.addProperty("key", "history:${n++}")
                    out.add(m)
                }
            }
        }
        return out
    }

    /** The conversation as Markdown, for `/export`. */
    fun markdown(title: String, lines: Sequence<String>): String = buildString {
        append("# ").append(title).append("\n")
        for (line in lines) {
            val o = parse(line) ?: continue
            if (o.isTrue("isSidechain") || o.isTrue("isMeta")) continue
            when (o.str("type")) {
                "user" -> promptText(o.obj("message")?.get("content"))?.let { append("\n## You\n\n").append(it).append("\n") }
                "assistant" -> (o.obj("message")?.get("content") as? JsonArray)?.forEach { b ->
                    val block = b as? JsonObject ?: return@forEach
                    when (block.str("type")) {
                        "text" -> append("\n## Claude\n\n").append(block.str("text") ?: "").append("\n")
                        "tool_use" -> append("\n> ").append(block.str("name") ?: "tool").append(": ")
                            .append(StreamEvents.toolSummary(block.obj("input"))).append("\n")
                    }
                }
            }
        }
    }

    private fun titleOf(o: JsonObject): String? = TITLE_KEYS.firstNotNullOfOrNull { o.str(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }
    private fun parse(line: String): JsonObject? = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.isTrue(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
}
