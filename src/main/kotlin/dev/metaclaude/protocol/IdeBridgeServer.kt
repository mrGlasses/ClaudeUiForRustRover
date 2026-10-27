package dev.metaclaude.protocol

import com.google.gson.JsonObject
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.extensions.IExtension
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.protocols.Protocol
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Loopback-only WebSocket server speaking the Claude Code IDE protocol (WebSocket variant of MCP).
 * Auth: the CLI must send header `x-claude-code-ide-authorization: <authToken from lock file>`.
 *
 * Subprotocol: if the CLI asks for `Sec-WebSocket-Protocol: mcp`, we agree to it (strict WS clients
 * close the connection when the server ignores a requested subprotocol). The empty Protocol("") keeps
 * clients that ask for nothing working.
 */
class IdeBridgeServer(
    private val authToken: String,
    private val dispatcher: McpDispatcher,
    private val onClientConnected: () -> Unit = {},
) : WebSocketServer(
    InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
    listOf<Draft>(Draft_6455(emptyList<IExtension>(), listOf(Protocol("mcp"), Protocol("")))),
) {

    private val started = CompletableFuture<Int>()

    /** Binds an OS-assigned port and returns it. */
    fun startAndGetPort(): Int {
        start()
        val p = started.get(5, TimeUnit.SECONDS)
        BridgeLog.log("bridge listening on ${address.address}:$p")
        return p
    }

    override fun onStart() {
        started.complete(port)
    }

    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket, draft: Draft, request: ClientHandshake,
    ): ServerHandshakeBuilder {
        val names = buildList { request.iterateHttpFields().forEach { add(it) } }
        val supplied = request.getFieldValue("x-claude-code-ide-authorization") ?: ""
        val ok = MessageDigest.isEqual(supplied.toByteArray(), authToken.toByteArray()) // constant-time
        BridgeLog.log(
            "handshake from ${conn.remoteSocketAddress}: authHeaderPresent=${supplied.isNotEmpty()} " +
                "authMatches=$ok subprotocol='${request.getFieldValue("Sec-WebSocket-Protocol")}' " +
                "userAgent='${request.getFieldValue("User-Agent")}' headers=$names",
        )
        if (!ok) throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "bad auth token")
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        BridgeLog.log("client connected")
        onClientConnected()
    }

    override fun onMessage(conn: WebSocket, message: String) {
        dispatcher.handle(message) { reply -> if (conn.isOpen) conn.send(reply) }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        BridgeLog.log("client closed: code=$code reason='$reason' byRemote=$remote")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        BridgeLog.log("server error: ${ex::class.java.name}: ${ex.message}")
    }

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
