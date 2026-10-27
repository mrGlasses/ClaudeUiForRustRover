package dev.metaclaude.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.extensions.IExtension
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.protocols.Protocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Plays the CLI side over a real WebSocket: same header and subprotocol as claude-code 2.1.311 (see bridge.log). */
private class FakeCli(port: Int, token: String?) : WebSocketClient(
    URI("ws://127.0.0.1:$port"),
    Draft_6455(emptyList<IExtension>(), listOf(Protocol("mcp"))),
    if (token != null) mapOf("x-claude-code-ide-authorization" to token) else emptyMap(),
) {
    val inbox = LinkedBlockingQueue<JsonObject>()
    override fun onOpen(handshake: ServerHandshake) = Unit
    override fun onMessage(message: String) { inbox.put(JsonParser.parseString(message).asJsonObject) }
    override fun onClose(code: Int, reason: String, remote: Boolean) = Unit
    override fun onError(ex: Exception) = Unit

    fun request(id: Int, method: String, params: String = "{}"): JsonObject {
        send("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
        return requireNotNull(inbox.poll(5, TimeUnit.SECONDS)) { "no reply to $method" }
    }
}

class BridgeServerTest {
    private val token = AuthToken.generate()
    private val server = IdeBridgeServer(token, McpDispatcher(ToolRegistry(listOf(FakeTool()))))
    private val port = server.startAndGetPort()

    @After
    fun tearDown() = server.stop(500)

    @Test
    fun `full CLI handshake works`() {
        val cli = FakeCli(port, token)
        assertTrue("connection with the right token must open", cli.connectBlocking(5, TimeUnit.SECONDS))

        val init = cli.request(0, "initialize")
        assertEquals("2025-03-26", init.getAsJsonObject("result").get("protocolVersion").asString)

        cli.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        cli.send("""{"jsonrpc":"2.0","method":"ide_connected","params":{}}""")

        val list = cli.request(1, "tools/list")
        assertEquals("echo", list.getAsJsonObject("result").getAsJsonArray("tools")[0].asJsonObject.get("name").asString)

        val call = cli.request(2, "tools/call", """{"name":"echo","arguments":{"value":"ok"}}""")
        assertNotNull(call.getAsJsonObject("result"))
        cli.closeBlocking()
    }

    @Test
    fun `wrong token is rejected`() {
        val cli = FakeCli(port, "0".repeat(32))
        assertFalse(cli.connectBlocking(5, TimeUnit.SECONDS))
    }

    @Test
    fun `missing token is rejected`() {
        val cli = FakeCli(port, null)
        assertFalse(cli.connectBlocking(5, TimeUnit.SECONDS))
    }

    @Test
    fun `server listens on loopback only`() {
        assertTrue(server.address.address.isLoopbackAddress)
    }
}
