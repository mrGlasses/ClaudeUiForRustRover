package dev.metaclaude.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines shaped like the spike transcript (CLI 2.1.312, STREAM-PROTOCOL.md). */
class StreamEventsTest {
    private fun map(json: String): List<JsonObject> = StreamEvents.toPanel(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `init gives session info`() {
        val out = map("""{"type":"system","subtype":"init","session_id":"s","model":"claude-opus-5-5","permissionMode":"default","tools":[]}""")
        assertEquals("sessionInfo", out.single().get("type").asString)
        assertEquals("claude-opus-5-5", out.single().get("model").asString)
    }

    @Test
    fun `text deltas are keyed by message and block`() {
        val out = map("""{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hel"}},"session_id":"s","parent_tool_use_id":null,"uuid":"u","api_message_id":"msg_1"}""")
        assertEquals("textDelta", out.single().get("type").asString)
        assertEquals("msg_1:1", out.single().get("key").asString)
        assertEquals("msg_1", out.single().get("messageId").asString)
        assertEquals("Hel", out.single().get("text").asString)
    }

    @Test
    fun `other stream events and json deltas are ignored`() {
        assertTrue(map("""{"type":"stream_event","event":{"type":"message_start","message":{}},"parent_tool_use_id":null,"api_message_id":"m"}""").isEmpty())
        assertTrue(map("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{"}},"parent_tool_use_id":null,"api_message_id":"m"}""").isEmpty())
    }

    @Test
    fun `assistant message gives final text and tool calls in order`() {
        val out = map("""{"type":"assistant","message":{"id":"msg_2","role":"assistant","content":[{"type":"text","text":"I'll write it."},{"type":"tool_use","id":"toolu_1","name":"Write","input":{"file_path":"C:\\tmp\\spike.txt","content":"hello"}}]},"parent_tool_use_id":null,"session_id":"s"}""")
        assertEquals(listOf("textFinal", "toolUse"), out.map { it.get("type").asString })
        assertEquals("msg_2", out[0].get("messageId").asString)
        assertEquals("msg_2:final:0", out[0].get("key").asString)
        assertEquals("Write", out[1].get("name").asString)
        assertEquals("C:\\tmp\\spike.txt", out[1].get("summary").asString)
    }

    @Test
    fun `tool results and interrupt marker`() {
        val ok = map("""{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_1","type":"tool_result","content":"File created successfully"}]},"parent_tool_use_id":null,"session_id":"s"}""")
        assertEquals("toolResult", ok.single().get("type").asString)
        assertEquals("toolu_1", ok.single().get("toolUseId").asString)
        assertEquals(false, ok.single().get("isError").asBoolean)
        val err = map("""{"type":"user","message":{"role":"user","content":[{"tool_use_id":"t","type":"tool_result","is_error":true,"content":[{"type":"text","text":"denied"}]}]},"parent_tool_use_id":null}""")
        assertEquals(true, err.single().get("isError").asBoolean)
        assertEquals("denied", err.single().get("preview").asString)
        val interrupted = map("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]},"parent_tool_use_id":null}""")
        assertEquals("notice", interrupted.single().get("type").asString)
    }

    @Test
    fun `result ends the turn with cost`() {
        val out = map("""{"type":"result","stop_reason":"end_turn","session_id":"s","total_cost_usd":0.123,"duration_ms":3000,"is_error":false}""")
        assertEquals("turnEnd", out.single().get("type").asString)
        assertEquals(0.123, out.single().get("costUsd").asDouble, 1e-9)
    }

    @Test
    fun `subagent output and unknown events are skipped`() {
        assertTrue(map("""{"type":"assistant","message":{"id":"m","content":[{"type":"text","text":"x"}]},"parent_tool_use_id":"toolu_9"}""").isEmpty())
        assertTrue(map("""{"type":"rate_limit_event","rate_limit_info":{}}""").isEmpty())
    }

    @Test
    fun `tool summary is one short line`() {
        val long = JsonObject().apply { addProperty("command", "a".repeat(500) + "\nsecond line") }
        val s = StreamEvents.toolSummary(long)
        assertTrue(s.length <= 201 && !s.contains('\n'))
        assertEquals("", StreamEvents.toolSummary(JsonObject()))
    }

    @Test
    fun `thinking blocks start, stream and stop`() {
        val start = map("""{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}},"parent_tool_use_id":null,"api_message_id":"msg_3"}""")
        assertEquals("thinkingStart", start.single().get("type").asString)
        assertEquals("msg_3:0", start.single().get("key").asString)
        val delta = map("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me"}},"parent_tool_use_id":null,"api_message_id":"msg_3"}""")
        assertEquals("thinkingDelta", delta.single().get("type").asString)
        assertEquals("Let me", delta.single().get("text").asString)
        val stop = map("""{"type":"stream_event","event":{"type":"content_block_stop","index":0},"parent_tool_use_id":null,"api_message_id":"msg_3"}""")
        assertEquals("blockStop", stop.single().get("type").asString)
        assertEquals("msg_3:0", stop.single().get("key").asString)
        // a text block start is not a thinking start
        assertTrue(map("""{"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}},"parent_tool_use_id":null,"api_message_id":"msg_3"}""").isEmpty())
    }

    @Test
    fun `TodoWrite becomes the to-do list, not a tool card`() {
        val out = map("""{"type":"assistant","message":{"id":"msg_4","role":"assistant","content":[{"type":"tool_use","id":"toolu_9","name":"TodoWrite","input":{"todos":[{"content":"Write tests","status":"completed","activeForm":"Writing tests"},{"content":"Fix bug","status":"in_progress","activeForm":"Fixing bug"},{"content":"Odd","status":"weird"}]}}]},"parent_tool_use_id":null}""")
        val todos = out.single()
        assertEquals("todos", todos.get("type").asString)
        val items = todos.getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(listOf("Write tests", "Fixing bug", "Odd"), items.map { it.get("text").asString })
        assertEquals(listOf("completed", "in_progress", "pending"), items.map { it.get("status").asString })
    }
}
