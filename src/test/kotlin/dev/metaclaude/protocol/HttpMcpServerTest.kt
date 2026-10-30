package dev.metaclaude.protocol

import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Plays the CLI's side of `--mcp-config` HTTP: POST JSON-RPC with a bearer token. */
class HttpMcpServerTest {
    private val token = AuthToken.generate()
    private val server = HttpMcpServer(
        token,
        McpDispatcher(ToolRegistry(listOf(FakeTool())), echoClientProtocolVersion = true),
    )
    private val http = HttpClient.newHttpClient()

    @After
    fun tearDown() = server.close()

    private fun post(body: String, auth: String? = "Bearer $token"): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI(server.url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { if (auth != null) header("Authorization", auth) }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `initialize echoes a known client protocol version`() {
        val r = post("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
        assertEquals(200, r.statusCode())
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
        val result = JsonParser.parseString(r.body()).asJsonObject.getAsJsonObject("result")
        assertEquals("2025-06-18", result.get("protocolVersion").asString)
    }

    @Test
    fun `unknown client version falls back to the default`() {
        val r = post("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")
        val result = JsonParser.parseString(r.body()).asJsonObject.getAsJsonObject("result")
        assertEquals("2025-03-26", result.get("protocolVersion").asString)
    }

    @Test
    fun `tools list and call work over HTTP`() {
        val list = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertTrue(list.body().contains("\"echo\""))
        val call = post("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"value":"hi"}}}""")
        val text = JsonParser.parseString(call.body()).asJsonObject.getAsJsonObject("result")
            .getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertEquals("hi", text)
    }

    @Test
    fun `notifications get 202 and no body`() {
        val r = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, r.statusCode())
        assertEquals("", r.body())
    }

    @Test
    fun `missing or wrong token is rejected`() {
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", auth = null).statusCode())
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", auth = "Bearer nope").statusCode())
    }

    @Test
    fun `GET is not offered`() {
        val req = HttpRequest.newBuilder(URI(server.url)).header("Authorization", "Bearer $token").GET().build()
        assertEquals(405, http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode())
    }

    @Test
    fun `mcp config points at this server with the token`() {
        val cfg = JsonParser.parseString(server.mcpConfigJson("clawed-ui")).asJsonObject
            .getAsJsonObject("mcpServers").getAsJsonObject("clawed-ui")
        assertEquals("http", cfg.get("type").asString)
        assertEquals(server.url, cfg.get("url").asString)
        assertEquals("Bearer $token", cfg.getAsJsonObject("headers").get("Authorization").asString)
        assertTrue(server.url.startsWith("http://127.0.0.1:"))
    }
}
