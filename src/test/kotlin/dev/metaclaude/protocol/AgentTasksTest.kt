package dev.metaclaude.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Events shaped like claude 2.1.316's task events (see AgentTasks). */
class AgentTasksTest {
    private fun map(json: String): List<JsonObject> = StreamEvents.toPanel(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `a subagent's life in the map`() {
        val started = map("""{"type":"system","subtype":"task_started","task_id":"t1","tool_use_id":"toolu_a","description":"Find TODOs","subagent_type":"Explore","task_type":"local_agent","is_backgrounded":false,"prompt":"Search for TODO","uuid":"u","session_id":"s"}""").single()
        assertEquals("agentTaskStarted", started.get("type").asString)
        assertEquals("t1", started.get("taskId").asString)
        assertEquals("toolu_a", started.get("toolUseId").asString)
        assertEquals("agent", started.get("kind").asString)
        assertEquals("Explore", started.get("subagentType").asString)
        assertFalse(started.get("background").asBoolean)

        val progress = map("""{"type":"system","subtype":"task_progress","task_id":"t1","description":"Find TODOs","usage":{"total_tokens":1200,"tool_uses":3,"duration_ms":4500},"last_tool_name":"Grep","uuid":"u","session_id":"s"}""").single()
        assertEquals(1200L, progress.get("tokens").asLong)
        assertEquals(3, progress.get("toolUses").asInt)
        assertEquals("Grep", progress.get("lastTool").asString)

        val moved = map("""{"type":"system","subtype":"task_updated","task_id":"t1","patch":{"is_backgrounded":true,"status":"running"},"uuid":"u","session_id":"s"}""").single()
        assertTrue(moved.get("background").asBoolean)
        assertEquals("running", moved.get("status").asString)

        val ended = map("""{"type":"system","subtype":"task_notification","task_id":"t1","status":"stopped","output_file":"/tmp/x","summary":"Stopped by user","uuid":"u","session_id":"s"}""").single()
        assertEquals("agentTaskEnded", ended.get("type").asString)
        assertEquals("stopped", ended.get("status").asString)
    }

    @Test
    fun `kinds decide the actions`() {
        assertEquals("shell", AgentTasks.kind("local_bash", null))
        assertEquals("agent", AgentTasks.kind("local_agent", "general-purpose"))
        assertEquals("agent", AgentTasks.kind(null, "Explore"))
        assertEquals("monitor", AgentTasks.kind("monitor", null))
        assertEquals("other", AgentTasks.kind("local_workflow", null))
        assertTrue(AgentTasks.hasOutput("shell"))
        assertFalse(AgentTasks.hasOutput("agent"))
    }

    @Test
    fun `live background set and subagent tool calls`() {
        val live = map("""{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"b1","task_type":"local_bash","description":"cargo build"}],"uuid":"u","session_id":"s"}""").single()
        assertEquals("agentTasksLive", live.get("type").asString)
        assertEquals("shell", live.getAsJsonArray("tasks")[0].asJsonObject.get("kind").asString)

        val tool = map("""{"type":"assistant","message":{"id":"m","content":[{"type":"tool_use","id":"x","name":"Grep","input":{"pattern":"TODO"}}]},"parent_tool_use_id":"toolu_a"}""").single()
        assertEquals("tool", tool.get("kind").asString)
        assertTrue(tool.get("text").asString.startsWith("Grep: "))
    }

    @Test
    fun `task output becomes plain text`() {
        val payload = JsonParser.parseString("{\"output\":\"\\u001b[32mCompiling\\u001b[0m foo\\r\\n 10%\\r 50%\\r100%\\n\",\"total_bytes\":9000,\"truncated\":true}").asJsonObject
        val out = AgentTasks.output(payload)
        assertEquals("Compiling foo\n100%\n", out.get("output").asString)
        assertTrue(out.get("truncated").asBoolean)
        assertEquals(9000L, out.get("totalBytes").asLong)
    }

    @Test
    fun `other system events are not task events`() {
        assertEquals(null, AgentTasks.system(JsonParser.parseString("""{"type":"system","subtype":"init"}""").asJsonObject))
    }
}
