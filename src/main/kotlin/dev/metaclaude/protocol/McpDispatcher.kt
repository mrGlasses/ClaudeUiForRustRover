package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture

/** One IDE tool exposed over MCP. `call` returns a future so blocking tools (openDiff) can defer the reply. */
interface IdeTool {
    val name: String
    val description: String
    val inputSchema: JsonObject
    fun call(args: JsonObject): CompletableFuture<JsonObject>
}

object Mcp {
    /** MCP tool result with one text item. */
    fun text(s: String): JsonObject = JsonObject().apply {
        add("content", JsonArray().apply {
            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", s) })
        })
    }
    /** MCP tool result with several text items (e.g. FILE_SAVED + final file contents). */
    fun texts(vararg items: String): JsonObject = JsonObject().apply {
        add("content", JsonArray().apply {
            items.forEach { s -> add(JsonObject().apply { addProperty("type", "text"); addProperty("text", s) }) }
        })
    }
    fun obj(vararg kv: Pair<String, JsonElement>) = JsonObject().apply { kv.forEach { add(it.first, it.second) } }
}

class ToolRegistry(tools: List<IdeTool>) {
    private val byName = tools.associateBy { it.name }

    fun describe(): JsonObject = JsonObject().apply {
        add("tools", JsonArray().apply {
            byName.values.forEach { t ->
                add(JsonObject().apply {
                    addProperty("name", t.name)
                    addProperty("description", t.description)
                    add("inputSchema", t.inputSchema)
                })
            }
        })
    }

    fun call(name: String, args: JsonObject): CompletableFuture<JsonObject> =
        byName[name]?.call(args) ?: CompletableFuture.failedFuture(IllegalArgumentException("Unknown tool: $name"))
}

/** Minimal JSON-RPC 2.0 / MCP (2025-03-26) handling for the IDE side. */
class McpDispatcher(
    private val tools: ToolRegistry,
    private val onIdeConnected: () -> Unit = {},
    private val logTag: String? = null,
    /** HTTP MCP (panel): answer with the client's protocol version when we know it. The IDE bridge keeps 2025-03-26. */
    private val echoClientProtocolVersion: Boolean = false,
) {
    fun handle(raw: String, reply: (String) -> Unit) {
        try {
            handleInner(raw) { out ->
                BridgeLog.log("  -> reply ${out.take(60).replace('\n', ' ')}", logTag) // head only: bodies may hold source code
                reply(out)
            }
        } catch (e: Exception) {
            BridgeLog.log("dispatch failed: ${e::class.java.name}: ${e.message}", logTag)
        }
    }

    private fun handleInner(raw: String, reply: (String) -> Unit) {
        val msg = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return
        val id = msg.get("id")                       // null => notification, never reply
        val method = msg.get("method")?.asString ?: return // a response from the client: ignore
        // Tool name only, never arguments (they can hold source code).
        val tool = if (method == "tools/call") msg.getAsJsonObject("params")?.get("name")?.asString else null
        BridgeLog.log("<- $method id=$id" + (if (tool != null) " tool=$tool" else ""), logTag)

        when (method) {
            "initialize" -> {
                val requested = msg.getAsJsonObject("params")?.get("protocolVersion")?.asString
                val version = if (echoClientProtocolVersion && requested in KNOWN_VERSIONS) requested!! else DEFAULT_VERSION
                BridgeLog.log("   initialize: client protocolVersion=$requested, answering $version", logTag)
                reply(result(id, initializeResult(version)))
            }
            "notifications/initialized" -> Unit
            "ide_connected" -> onIdeConnected()       // CLI says hello; see "notification timing" in SKETCH.md
            "ping" -> reply(result(id, JsonObject()))
            "tools/list" -> reply(result(id, tools.describe()))
            "tools/call" -> {
                val p = msg.getAsJsonObject("params")
                val name = p.get("name").asString
                val args = p.getAsJsonObject("arguments") ?: JsonObject()
                tools.call(name, args).whenComplete { res, err ->
                    if (err != null) reply(error(id, -32000, err.message ?: "tool failed"))
                    else reply(result(id, res))
                }
            }
            else -> if (id != null) reply(error(id, -32601, "Method not found: $method"))
        }
    }

    private fun initializeResult(version: String) = JsonObject().apply {
        addProperty("protocolVersion", version)
        add("capabilities", JsonObject().apply {
            add("tools", JsonObject().apply { addProperty("listChanged", true) })
        })
        add("serverInfo", JsonObject().apply {
            addProperty("name", "clawed-ui"); addProperty("version", "0.0.1")
        })
    }

    private fun envelope(id: JsonElement?) = JsonObject().apply {
        addProperty("jsonrpc", "2.0"); add("id", id)
    }
    private fun result(id: JsonElement?, r: JsonObject) = envelope(id).apply { add("result", r) }.toString()
    private fun error(id: JsonElement?, code: Int, message: String) = envelope(id).apply {
        add("error", JsonObject().apply { addProperty("code", code); addProperty("message", message) })
    }.toString()

    private companion object {
        const val DEFAULT_VERSION = "2025-03-26"
        val KNOWN_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
    }
}
