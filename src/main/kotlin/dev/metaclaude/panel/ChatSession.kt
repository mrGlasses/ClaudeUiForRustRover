package dev.metaclaude.panel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import dev.metaclaude.protocol.AuthToken
import dev.metaclaude.protocol.BridgeLog
import dev.metaclaude.protocol.HttpMcpServer
import dev.metaclaude.protocol.McpDispatcher
import dev.metaclaude.protocol.StreamEvents
import dev.metaclaude.protocol.ToolRegistry
import dev.metaclaude.tools.GetDiagnostics
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One chat-panel conversation = one `claude -p` process in stream-json mode (STREAM-PROTOCOL.md).
 * IDE tools come from our own HTTP MCP server (spike v3), not from the IDE bridge.
 *
 * Phase 7a: there are no permission cards yet, so every permission request is DENIED with a clear message.
 * Reads inside the project and our IDE tools work; edits and commands are refused until Phase 8.
 * All callbacks run on background threads.
 */
class ChatSession(
    private val project: Project,
    exe: String,
    root: Path,
    parent: Disposable,
    private val onPanelMessage: (JsonObject) -> Unit,
    private val onExit: (exitCode: Int) -> Unit,
    todoTools: Boolean = false,
) : Disposable {
    private val tag = "${project.name}/panel"
    private val mcp = HttpMcpServer(
        AuthToken.generate(),
        McpDispatcher(ToolRegistry(listOf(GetDiagnostics(project))), logTag = tag, echoClientProtocolVersion = true),
        logTag = tag,
    )
    private val mcpConfig: Path = Files.createTempFile("claude-ui-mcp", ".json").also { f ->
        runCatching { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")) }
        Files.writeString(f, mcp.mcpConfigJson(MCP_NAME)) // holds the token: owner-only, deleted on dispose
    }
    private val process: Process
    private val stdin: BufferedWriter
    private var counter = 0
    @Volatile private var closed = false

    init {
        Disposer.register(parent, this)
        val cmd = listOf(
            exe, "-p",
            "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
            "--include-partial-messages",
            "--permission-mode", "default",
            "--permission-prompt-tool", "stdio",
            "--mcp-config", mcpConfig.toString(),
        )
        process = ProcessBuilder(cmd).directory(root.toFile()).apply {
            if (todoTools) environment()["CLAUDE_CODE_ENABLE_TODO_TOOLS"] = "1"
        }.start()
        stdin = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        BridgeLog.log("chat session started (pid ${process.pid()}, to-do tools ${if (todoTools) "on" else "off"})", tag)

        thread(name = "claude-ui-chat-out", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line -> onLine(line) }
            }
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            BridgeLog.log("chat session ended (exit $code)", tag)
            if (!closed) onExit(code)
        }
        thread(name = "claude-ui-chat-err", isDaemon = true) {
            // stderr is for errors; log only a short head of each line (no message bodies expected here).
            runCatching { process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { BridgeLog.log("chat stderr: ${it.take(200)}", tag) } }
        }
        write(control("initialize", JsonObject().apply { add("hooks", null) }))
    }

    private fun onLine(line: String) {
        val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return
        if (event.get("type")?.asString == "control_request") return answerControlRequest(event)
        StreamEvents.toPanel(event).forEach { m ->
            // Tool names only (never inputs): shows which tools the CLI version really uses (to-do, diagnostics...).
            if (m.get("type")?.asString == "toolUse") BridgeLog.log("tool use: ${m.get("name")?.asString}", tag)
            if (m.get("type")?.asString in setOf("todos", "taskCreate", "taskUpdate")) BridgeLog.log("tool use: to-do (${m.get("type")?.asString})", tag)
            onPanelMessage(m)
        }
    }

    /** Phase 7a: deny every permission request (no cards yet), and tell the user why. */
    private fun answerControlRequest(event: JsonObject) {
        val id = event.get("request_id")?.asString ?: return
        val request = event.getAsJsonObject("request")
        if (request?.get("subtype")?.asString != "can_use_tool") {
            write(controlResponse(id, JsonObject())) // nothing else is expected; answer so the CLI never waits
            return
        }
        val tool = request.get("display_name")?.asString ?: request.get("tool_name")?.asString ?: "a tool"
        BridgeLog.log("permission request for '$tool' (tool_name=${request.get("tool_name")?.asString}) denied (cards come in Phase 8)", tag)
        write(controlResponse(id, JsonObject().apply {
            addProperty("behavior", "deny")
            addProperty("message", "The Clawed UI chat panel cannot approve tool use yet. Ask the user to use a Claude terminal session for edits and commands.")
        }))
        onPanelMessage(JsonObject().apply {
            addProperty("type", "notice")
            addProperty("text", "Claude asked to use $tool. Permission cards arrive in the next phase, so it was denied. Use Tools > New Claude Session for edits and commands.")
        })
    }

    fun send(text: String) = write(JsonObject().apply {
        addProperty("type", "user")
        add("message", JsonObject().apply { addProperty("role", "user"); addProperty("content", text) })
        add("parent_tool_use_id", null)
    })

    fun interrupt() = write(control("interrupt", JsonObject()))

    @Synchronized
    private fun write(json: JsonObject) {
        if (closed) return
        try {
            stdin.write(json.toString()); stdin.write("\n"); stdin.flush() // one object per line, \n only
        } catch (e: Exception) {
            BridgeLog.log("chat session write failed: ${e::class.java.simpleName}", tag)
        }
    }

    private fun control(subtype: String, body: JsonObject) = JsonObject().apply {
        addProperty("type", "control_request")
        addProperty("request_id", "ui_${++counter}")
        add("request", body.apply { addProperty("subtype", subtype) })
    }

    private fun controlResponse(id: String, payload: JsonObject) = JsonObject().apply {
        addProperty("type", "control_response")
        add("response", JsonObject().apply {
            addProperty("subtype", "success")
            addProperty("request_id", id)
            add("response", payload)
        })
    }

    override fun dispose() {
        closed = true
        runCatching { stdin.close() } // the CLI exits when stdin closes
        val proc = process
        AppExecutorUtil.getAppExecutorService().execute {
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
        mcp.close()
        runCatching { Files.deleteIfExists(mcpConfig) }
    }

    private companion object {
        const val MCP_NAME = "clawed-ui"
    }
}
