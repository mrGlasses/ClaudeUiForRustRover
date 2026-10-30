package dev.metaclaude.protocol

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MCP over HTTP (streamable HTTP, JSON responses only) for chat-panel sessions, which can't use the IDE bridge
 * (`-p` never attaches to it, spike v2). The panel passes this server to the CLI with `--mcp-config`, like VS Code's
 * panel does with its `claude-vscode` server.
 *
 * - Loopback only, OS-assigned port, `Authorization: Bearer <token>` compared in constant time (401 otherwise).
 * - POST /mcp: one JSON-RPC message. Requests get the dispatcher's reply as application/json; notifications get 202.
 * - GET /mcp (server-initiated SSE stream) is not offered: 405, which the MCP spec allows.
 * - Never logs bodies: the dispatcher logs method and tool names only.
 */
class HttpMcpServer(
    private val token: String,
    private val dispatcher: McpDispatcher,
    private val logTag: String? = null,
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "claude-ui-panel-mcp").apply { isDaemon = true }
    }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/mcp") { ex -> handle(ex) }
        executor = this@HttpMcpServer.executor
        start()
    }

    val port: Int get() = server.address.port
    val url: String get() = "http://127.0.0.1:$port/mcp"

    init {
        BridgeLog.log("panel MCP server listening on ${server.address.address}:$port", logTag)
    }

    private fun handle(ex: HttpExchange) = ex.use {
        try {
            val auth = ex.requestHeaders.getFirst("Authorization") ?: ""
            if (!MessageDigest.isEqual(auth.toByteArray(), "Bearer $token".toByteArray())) {
                BridgeLog.log("panel MCP: rejected ${ex.requestMethod} without a valid token", logTag)
                return@use respond(ex, 401, null)
            }
            when (ex.requestMethod) {
                "POST" -> post(ex)
                "DELETE" -> respond(ex, 200, null) // session end: nothing to clean up
                else -> {
                    ex.responseHeaders.add("Allow", "POST, DELETE")
                    respond(ex, 405, null)
                }
            }
        } catch (e: Exception) {
            BridgeLog.log("panel MCP error: ${e::class.java.name}: ${e.message}", logTag)
            runCatching { respond(ex, 500, null) }
        }
    }

    private fun post(ex: HttpExchange) {
        val body = ex.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
        val isRequest = runCatching { JsonParser.parseString(body).asJsonObject.has("id") }.getOrDefault(false)
        if (!isRequest) {
            dispatcher.handle(body) { }
            return respond(ex, 202, null) // notification (or a client response): accepted, no body
        }
        val reply = CompletableFuture<String>()
        dispatcher.handle(body) { reply.complete(it) }
        val out = runCatching { reply.get(REPLY_TIMEOUT_S, TimeUnit.SECONDS) }.getOrNull()
        if (out == null) return respond(ex, 504, null)
        respond(ex, 200, out)
    }

    private fun respond(ex: HttpExchange, code: Int, body: String?) {
        if (body == null) {
            ex.sendResponseHeaders(code, -1)
            return
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** `--mcp-config` content naming this server [name]. Contains the token: write it to an owner-only file. */
    fun mcpConfigJson(name: String): String =
        """{"mcpServers":{"$name":{"type":"http","url":"$url","headers":{"Authorization":"Bearer $token"}}}}"""

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private companion object {
        const val REPLY_TIMEOUT_S = 120L
    }
}
