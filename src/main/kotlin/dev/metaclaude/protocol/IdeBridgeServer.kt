package dev.metaclaude.protocol

import com.google.gson.JsonObject
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.framing.CloseFrame
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Loopback-only WebSocket server speaking the Claude Code IDE protocol (WebSocket variant of MCP).
 * Auth: the CLI must send header `x-claude-code-ide-authorization: <authToken from lock file>`.
 */
class IdeBridgeServer(
    private val authToken: String,
    private val dispatcher: McpDispatcher,
    private val onClientConnected: () -> Unit = {},
) : WebSocketServer(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) {

    private val started = CompletableFuture<Int>()

    /** Binds an OS-assigned port and returns it. */
    fun startAndGetPort(): Int {
        start()
        return started.get(5, TimeUnit.SECONDS)
    }

    override fun onStart() {
        started.complete(port)
    }

    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket, draft: Draft, request: ClientHandshake,
    ): ServerHandshakeBuilder {
        val supplied = request.getFieldValue("x-claude-code-ide-authorization") ?: ""
        // constant-time compare
        if (!MessageDigest.isEqual(supplied.toByteArray(), authToken.toByteArray())) {
            throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "bad auth token")
        }
        // SPIKE: log request headers once (Sec-WebSocket-Protocol etc.) to see exactly what the CLI sends.
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) = onClientConnected()

    override fun onMessage(conn: WebSocket, message: String) {
        dispatcher.handle(message) { reply -> if (conn.isOpen) conn.send(reply) }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) = Unit
    override fun onError(conn: WebSocket?, ex: Exception) = Unit // TODO: route to IDE log

    /** IDE -> CLI notifications: selection_changed, at_mentioned. */
    fun broadcastNotification(method: String, params: JsonObject) {
        val msg = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            add("params", params)
        }
        broadcast(msg.toString())
    }
}
