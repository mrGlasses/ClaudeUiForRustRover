package dev.metaclaude.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

/** Fake tool: echoes `value`, fails on `fail=true`, or waits for the test to complete it (like openDiff). */
class FakeTool(override val name: String = "echo") : IdeTool {
    override val description = "test tool"
    override val inputSchema = JsonObject().apply { addProperty("type", "object") }
    var deferred: CompletableFuture<JsonObject>? = null

    override fun call(args: JsonObject): CompletableFuture<JsonObject> {
        if (args.get("fail")?.asBoolean == true) return CompletableFuture.failedFuture(IllegalStateException("boom"))
        if (args.get("defer")?.asBoolean == true) return CompletableFuture<JsonObject>().also { deferred = it }
        return CompletableFuture.completedFuture(Mcp.text(args.get("value")?.asString ?: ""))
    }
}

class McpDispatcherTest {
    private val tool = FakeTool()
    private val replies = mutableListOf<JsonObject>()
    private var connectedCalls = 0
    private val dispatcher = McpDispatcher(ToolRegistry(listOf(tool)), onIdeConnected = { connectedCalls++ })

    private fun send(json: String) = dispatcher.handle(json) { replies += JsonParser.parseString(it).asJsonObject }

    @Test
    fun `initialize returns protocol version and server info`() {
        send("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{}}""")
        val result = replies.single().getAsJsonObject("result")
        assertEquals("2025-03-26", result.get("protocolVersion").asString)
        assertTrue(result.has("serverInfo"))
        assertEquals(0, replies.single().get("id").asInt)
    }

    @Test
    fun `notifications never get a reply`() {
        send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        send("""{"jsonrpc":"2.0","method":"ide_connected","params":{}}""")
        send("""{"jsonrpc":"2.0","method":"some_unknown_notification"}""")
        assertTrue(replies.isEmpty())
        assertEquals(1, connectedCalls)
    }

    @Test
    fun `unknown request gets method-not-found`() {
        send("""{"jsonrpc":"2.0","id":7,"method":"nope"}""")
        assertEquals(-32601, replies.single().getAsJsonObject("error").get("code").asInt)
        assertEquals(7, replies.single().get("id").asInt)
    }

    @Test
    fun `tools list contains the registered tool`() {
        send("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        val tools = replies.single().getAsJsonObject("result").getAsJsonArray("tools")
        assertEquals("echo", tools[0].asJsonObject.get("name").asString)
    }

    @Test
    fun `tools call returns MCP text content`() {
        send("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"value":"hi"}}}""")
        val content = replies.single().getAsJsonObject("result").getAsJsonArray("content")
        assertEquals("text", content[0].asJsonObject.get("type").asString)
        assertEquals("hi", content[0].asJsonObject.get("text").asString)
    }

    @Test
    fun `failing tool and unknown tool become JSON-RPC errors`() {
        send("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"fail":true}}}""")
        send("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"missing","arguments":{}}}""")
        assertEquals(2, replies.size)
        replies.forEach { assertTrue(it.has("error")) }
    }

    @Test
    fun `blocking tool replies only when completed`() {
        send("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"echo","arguments":{"defer":true}}}""")
        assertTrue("must not reply before the user decides", replies.isEmpty())
        tool.deferred!!.complete(Mcp.text("FILE_SAVED"))
        val text = replies.single().getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertEquals("FILE_SAVED", text)
    }

    @Test
    fun `malformed input is ignored`() {
        send("not json")
        send("""{"jsonrpc":"2.0","id":9,"result":{}}""") // a response from the client, not a request
        assertTrue(replies.isEmpty())
    }

    @Test
    fun `auth tokens are 32 lowercase hex chars and unique`() {
        val a = AuthToken.generate()
        val b = AuthToken.generate()
        assertTrue(a.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(a, b)
        assertFalse(a.isBlank())
    }
}
