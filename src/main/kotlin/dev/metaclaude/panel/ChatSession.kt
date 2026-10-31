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
import dev.metaclaude.protocol.Permissions
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
 * Phase 8: permission requests become cards in the panel; each is answered exactly once (allow, deny, or cancelled by the CLI).
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
    thinkingSummaries: Boolean = false,
    permissionMode: String = "default",
    allowBypass: Boolean = false,
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
    // Extra Claude Code settings as a file, not inline JSON: claude.cmd goes through cmd.exe, which mangles quotes.
    private val settingsFile: Path? = if (!thinkingSummaries) null else
        Files.createTempFile("clawed-ui-settings", ".json").also { Files.writeString(it, """{"showThinkingSummaries":true}""") }
    private val process: Process
    private val stdin: BufferedWriter
    private var counter = 0
    @Volatile private var closed = false
    val bypassAllowedAtStart = allowBypass

    init {
        Disposer.register(parent, this)
        val cmd = listOf(
            exe, "-p",
            "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
            "--include-partial-messages",
            "--permission-mode", permissionMode,
            "--permission-prompt-tool", "stdio",
            "--mcp-config", mcpConfig.toString(),
        ) + (settingsFile?.let { listOf("--settings", it.toString()) } ?: emptyList()) +
            (if (allowBypass) listOf("--allow-dangerously-skip-permissions") else emptyList())
        process = ProcessBuilder(cmd).directory(root.toFile()).apply {
            if (todoTools) environment()["CLAUDE_CODE_ENABLE_TODO_TOOLS"] = "1"
        }.start()
        stdin = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        BridgeLog.log("chat session started (pid ${process.pid()}, to-do tools ${if (todoTools) "on" else "off"}, thinking summaries ${if (thinkingSummaries) "on" else "off"}, mode $permissionMode${if (allowBypass) ", bypass allowed" else ""})", tag)

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
        when (event.get("type")?.asString) {
            "control_request" -> return answerControlRequest(event)
            "control_cancel_request" -> return cancelRequest(event)
            "control_response" -> return onControlResponse(event)
        }
        StreamEvents.toPanel(event).forEach { m ->
            // Tool names only (never inputs): shows which tools the CLI version really uses (to-do, diagnostics...).
            if (m.get("type")?.asString == "toolUse") BridgeLog.log("tool use: ${m.get("name")?.asString}", tag)
            if (m.get("type")?.asString in setOf("todos", "taskCreate", "taskUpdate")) BridgeLog.log("tool use: to-do (${m.get("type")?.asString})", tag)
            onPanelMessage(m)
        }
    }

    /** A `can_use_tool` request waiting for the user's answer in the panel. */
    private class Pending(val tool: String, val input: JsonObject, val suggestions: List<JsonObject>)

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()
    private val modeRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Permission requests become panel cards (Phase 8). Anything else is answered at once so the CLI never waits. */
    private fun answerControlRequest(event: JsonObject) {
        val id = event.get("request_id")?.asString ?: return
        val request = event.getAsJsonObject("request")
        if (request?.get("subtype")?.asString != "can_use_tool") {
            write(controlResponse(id, JsonObject()))
            return
        }
        val tool = request.get("tool_name")?.asString ?: "tool"
        val input = request.get("input") as? JsonObject ?: JsonObject()
        val suggestions = (request.get("permission_suggestions") as? com.google.gson.JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        pending[id] = Pending(tool, input, suggestions)
        BridgeLog.log("permission request for '$tool' shown (${suggestions.size} suggestion(s))", tag)
        onPanelMessage(Permissions.card(id, request))
    }

    /** The CLI withdrew a request (e.g. after an interrupt): close its card. */
    private fun cancelRequest(event: JsonObject) {
        val id = event.get("request_id")?.asString ?: return
        if (pending.remove(id) != null) resolved(id, "Cancelled")
    }

    /** Our own control requests: only mode switches can fail in a way the user should see. */
    private fun onControlResponse(event: JsonObject) {
        val r = event.getAsJsonObject("response") ?: return
        val id = r.get("request_id")?.asString ?: return
        if (!modeRequests.remove(id)) return
        if (r.get("subtype")?.asString == "error") {
            val err = r.get("error")?.takeIf { it.isJsonPrimitive }?.asString?.take(300) ?: "unknown error"
            BridgeLog.log("set_permission_mode failed: $err", tag)
            onPanelMessage(JsonObject().apply { addProperty("type", "notice"); addProperty("text", "Couldn't switch mode: $err") })
        }
    }

    fun hasPending(id: String) = pending.containsKey(id)

    /** The tool name and suggestion of a pending request (to word the native confirmation). */
    fun suggestionOf(id: String, index: Int): JsonObject? = pending[id]?.suggestions?.getOrNull(index)

    /**
     * The user's answer from the panel, already validated by ChatPanel (request pending, suggestion index in range,
     * native confirmation done for permanent rules). Each request is answered exactly once.
     */
    fun answer(id: String, allow: Boolean, suggestionIndex: Int?, destination: String?, message: String?, answers: JsonObject?, response: String?) {
        val p = pending.remove(id) ?: return
        val payload: JsonObject
        val outcome: String
        if (!allow) {
            val text = message?.trim()?.take(Permissions.MESSAGE_MAX)?.takeIf { it.isNotEmpty() }
            payload = Permissions.deny(
                when {
                    p.tool == "ExitPlanMode" && text != null -> "The user wants changes to the plan before you start: $text"
                    text != null -> "The user denied this and said: $text"
                    p.tool == "AskUserQuestion" -> "The user declined to answer."
                    else -> "The user denied this action."
                },
            )
            outcome = if (text != null) "Denied, with your instructions" else "Denied"
        } else if (p.tool == "AskUserQuestion") {
            val input = Permissions.answeredInput(p.input, answers, response)
            if (input == null) { pending[id] = p; return } // nothing valid: keep the card open
            payload = Permissions.allow(input)
            outcome = "Answered"
        } else {
            val suggestion = suggestionIndex?.let { p.suggestions.getOrNull(it) }?.let { Permissions.withDestination(it, destination) }
            payload = Permissions.allow(p.input, suggestion)
            outcome = when {
                p.tool == "ExitPlanMode" -> "Plan approved"
                suggestion != null -> "Allowed (${Permissions.suggestionLabel(suggestion).removePrefix("Yes, and ")}, ${Permissions.destinationLabel(suggestion.get("destination")?.asString)})"
                else -> "Allowed once"
            }
        }
        BridgeLog.log("permission for '${p.tool}': $outcome", tag)
        write(controlResponse(id, payload))
        resolved(id, outcome)
    }

    private fun resolved(id: String, outcome: String) = onPanelMessage(JsonObject().apply {
        addProperty("type", "permissionResolved")
        addProperty("requestId", id)
        addProperty("outcome", outcome)
    })

    fun setMode(mode: String) {
        val req = control("set_permission_mode", JsonObject().apply { addProperty("mode", mode) })
        modeRequests.add(req.get("request_id").asString)
        write(req)
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
        settingsFile?.let { f -> runCatching { Files.deleteIfExists(f) } }
    }

    private companion object {
        const val MCP_NAME = "clawed-ui"
    }
}
