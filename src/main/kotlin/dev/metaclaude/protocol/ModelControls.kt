package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * Phase 12a: model picker, effort, Ultracode, extended thinking and the context indicator, through the CLI's own control
 * requests (schema of claude 2.1.316):
 *
 *  - `list_models` → {models: [{value, displayName, description, supportedEffortLevels?, disabled?, ...}]}
 *  - `get_settings` → {effective, sources, applied: {model, effort, ultracode?, ultracodeAvailable?}}
 *  - `set_model {model}`; `apply_flag_settings {settings: {effortLevel | ultracode}}` (session only, like /effort);
 *    `set_max_thinking_tokens {max_thinking_tokens}` (0 = off, null = the session's default)
 *  - `get_context_usage {detail: "summary"}` → {totalTokens, maxTokens, percentage, ...}
 *
 * Pure: unit-tested. Everything the panel gets is bounded plain text.
 */
object ModelControls {
    val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

    /** The picker's rows: selectable models only, with the effort levels each supports. */
    fun models(payload: JsonObject?): JsonArray = JsonArray().also { out ->
        (payload?.get("models") as? JsonArray)?.take(50)?.forEach { el ->
            val m = el as? JsonObject ?: return@forEach
            val value = m.str("value")?.take(120) ?: return@forEach
            out.add(JsonObject().apply {
                addProperty("value", value)
                addProperty("displayName", (m.str("displayName") ?: value).take(80))
                addProperty("description", (m.str("description") ?: "").take(300))
                addProperty("disabled", m.isTrue("disabled"))
                add("efforts", JsonArray().apply {
                    (m.get("supportedEffortLevels") as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                        ?.filter { it in EFFORTS }?.forEach { add(it) }
                })
                m.str("resolvedModel")?.let { addProperty("resolvedModel", it.take(120)) }
            })
        }
    }

    /** What is in effect now: model, effort, Ultracode (and whether it can be offered), thinking default. */
    fun current(payload: JsonObject?): JsonObject {
        val applied = payload?.get("applied") as? JsonObject ?: JsonObject()
        val effective = payload?.get("effective") as? JsonObject ?: JsonObject()
        return JsonObject().apply {
            applied.str("model")?.let { addProperty("model", it.take(120)) }
            applied.str("effort")?.takeIf { it in EFFORTS }?.let { addProperty("effort", it) }
            addProperty("ultracode", applied.isTrue("ultracode"))
            addProperty("ultracodeAvailable", applied.isTrue("ultracodeAvailable"))
            // Thinking is on unless the user's settings turned it off (alwaysThinkingEnabled: false).
            addProperty("thinkingDefault", effective.get("alwaysThinkingEnabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true)
        }
    }

    /** Request bodies, each only for a value the panel may send (rule 4). Null means "refuse". */
    fun setModel(model: String?, known: Collection<String>): JsonObject? =
        model?.takeIf { it in known || it == "default" }?.let { JsonObject().apply { addProperty("model", it) } }

    fun setEffort(level: String?): JsonObject? =
        level?.takeIf { it in EFFORTS }?.let { flagSettings { addProperty("effortLevel", it) } }

    fun setUltracode(on: Boolean): JsonObject = flagSettings { addProperty("ultracode", on) }

    /** Off is a zero budget; on goes back to the session's default, or to a budget when that default is off. */
    fun setThinking(on: Boolean, defaultOn: Boolean): JsonObject = JsonObject().apply {
        when {
            !on -> addProperty("max_thinking_tokens", 0)
            defaultOn -> add("max_thinking_tokens", JsonNull.INSTANCE)
            else -> addProperty("max_thinking_tokens", THINKING_BUDGET)
        }
    }

    const val THINKING_BUDGET = 31_999

    /** `get_context_usage` as the indicator needs it. */
    fun context(payload: JsonObject?): JsonObject? {
        payload ?: return null
        val max = payload.long("maxTokens").takeIf { it > 0 } ?: return null
        val total = payload.long("totalTokens")
        val pct = payload.get("percentage")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }
            ?: (total * 100.0 / max)
        return JsonObject().apply {
            addProperty("totalTokens", total)
            addProperty("maxTokens", max)
            addProperty("percentage", pct.coerceIn(0.0, 100.0))
        }
    }

    /**
     * How long the prompt cache lasts after the turn, from the turn's cache writes: 60 minutes if it wrote to the 1-hour
     * cache, 5 if only to the 5-minute one, null when it wrote nothing (a pure cache read keeps the TTL it had).
     */
    fun cacheTtlMinutes(usage: JsonObject?): Int? {
        val c = usage?.get("cache_creation") as? JsonObject ?: return null
        return when {
            c.long("ephemeral_1h_input_tokens") > 0 -> 60
            c.long("ephemeral_5m_input_tokens") > 0 -> 5
            else -> null
        }
    }

    private fun flagSettings(fill: JsonObject.() -> Unit) = JsonObject().apply { add("settings", JsonObject().apply(fill)) }
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.isTrue(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    private fun JsonObject.long(key: String): Long = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
}
