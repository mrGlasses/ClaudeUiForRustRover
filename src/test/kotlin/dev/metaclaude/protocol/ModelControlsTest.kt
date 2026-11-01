package dev.metaclaude.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers shaped like claude 2.1.316's list_models, get_settings and get_context_usage. */
class ModelControlsTest {
    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    @Test
    fun `models keep only what the picker shows`() {
        val rows = ModelControls.models(obj("""{"models":[
            {"value":"default","displayName":"Default (recommended)","description":"Opus 5.5","supportsEffort":true,"supportedEffortLevels":["low","medium","high","xhigh","max","bogus"]},
            {"value":"sonnet","resolvedModel":"claude-sonnet-5-5","displayName":"Sonnet","description":"Fast","disabled":true},
            {"displayName":"no value"}]}"""))
        assertEquals(2, rows.size())
        val first = rows[0].asJsonObject
        assertEquals("default", first.get("value").asString)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), first.getAsJsonArray("efforts").map { it.asString })
        assertTrue(rows[1].asJsonObject.get("disabled").asBoolean)
        assertEquals("claude-sonnet-5-5", rows[1].asJsonObject.get("resolvedModel").asString)
    }

    @Test
    fun `current settings`() {
        val c = ModelControls.current(obj("""{"effective":{"alwaysThinkingEnabled":false},"sources":[],"applied":{"model":"claude-opus-5-5","effort":"high","ultracode":false,"ultracodeAvailable":true}}"""))
        assertEquals("claude-opus-5-5", c.get("model").asString)
        assertEquals("high", c.get("effort").asString)
        assertTrue(c.get("ultracodeAvailable").asBoolean)
        assertFalse(c.get("thinkingDefault").asBoolean)
        assertTrue(ModelControls.current(null).get("thinkingDefault").asBoolean)
    }

    @Test
    fun `only known values become requests`() {
        assertEquals("sonnet", ModelControls.setModel("sonnet", listOf("default", "sonnet"))!!.get("model").asString)
        assertNull(ModelControls.setModel("gpt-5", listOf("default", "sonnet")))
        assertEquals("xhigh", ModelControls.setEffort("xhigh")!!.getAsJsonObject("settings").get("effortLevel").asString)
        assertNull(ModelControls.setEffort("turbo"))
        assertTrue(ModelControls.setUltracode(true).getAsJsonObject("settings").get("ultracode").asBoolean)
        assertEquals(0, ModelControls.setThinking(false, true).get("max_thinking_tokens").asInt)
        assertTrue(ModelControls.setThinking(true, true).get("max_thinking_tokens").isJsonNull)
        assertEquals(ModelControls.THINKING_BUDGET, ModelControls.setThinking(true, false).get("max_thinking_tokens").asInt)
    }

    @Test
    fun `context and cache clock`() {
        val c = ModelControls.context(obj("""{"categories":[],"totalTokens":46000,"maxTokens":200000,"rawMaxTokens":200000,"percentage":23,"model":"m"}"""))!!
        assertEquals(23.0, c.get("percentage").asDouble, 1e-9)
        assertEquals(46000L, c.get("totalTokens").asLong)
        assertNull(ModelControls.context(obj("""{"totalTokens":1}""")))

        assertEquals(60, ModelControls.cacheTtlMinutes(obj("""{"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":4100}}""")))
        assertEquals(5, ModelControls.cacheTtlMinutes(obj("""{"cache_creation":{"ephemeral_5m_input_tokens":300,"ephemeral_1h_input_tokens":0}}""")))
        assertNull(ModelControls.cacheTtlMinutes(obj("""{"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":0}}""")))
        val turn = StreamEvents.toPanel(obj("""{"type":"result","subtype":"success","is_error":false,"duration_ms":10,"usage":{"cache_creation":{"ephemeral_1h_input_tokens":12}},"total_cost_usd":0.1}""")).single()
        assertEquals(60, turn.get("cacheTtlMin").asInt)
    }
}
