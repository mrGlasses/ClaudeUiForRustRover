package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Phase 14b: bookmarked responses (VS Code's Bookmarks panel), kept in the project's workspace file by SessionNotes as small
 * JSON strings: {id, session, title, text, time}. And `/btw` side questions: control request
 * `side_question {question, history: [{question, response}]}`, answered with {response, synthetic?} (claude 2.1.316;
 * it answers from the conversation without adding to it, even while a turn runs). Pure: unit-tested.
 */
object Bookmarks {
    const val MAX = 200
    const val TEXT_MAX = 20_000
    const val SIDE_HISTORY = 20

    fun make(id: String, session: String, title: String, text: String, time: Long): String = JsonObject().apply {
        addProperty("id", id)
        addProperty("session", session)
        addProperty("title", title.take(120))
        addProperty("text", text.take(TEXT_MAX))
        addProperty("time", time)
    }.toString()

    private fun parse(s: String): JsonObject? = runCatching { JsonParser.parseString(s).asJsonObject }.getOrNull()

    fun idOf(s: String): String? = parse(s)?.get("id")?.takeIf { it.isJsonPrimitive }?.asString

    fun find(all: List<String>, id: String?): JsonObject? = all.asSequence().mapNotNull { parse(it) }.firstOrNull { it.get("id")?.asString == id }

    /** Newest first, for the panel. */
    fun list(all: List<String>): JsonArray = JsonArray().apply { all.asReversed().mapNotNull { parse(it) }.forEach { add(it) } }

    /** The side_question request body with the earlier exchanges of this conversation (newest [SIDE_HISTORY]). */
    fun sideQuestion(question: String, history: List<Pair<String, String>>): JsonObject = JsonObject().apply {
        addProperty("question", question)
        add("history", JsonArray().apply {
            history.takeLast(SIDE_HISTORY).forEach { (q, r) -> add(JsonObject().apply { addProperty("question", q); addProperty("response", r) }) }
        })
    }

    fun sideAnswer(payload: JsonObject?): String? =
        payload?.get("response")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
