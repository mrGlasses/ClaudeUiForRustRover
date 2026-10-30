package dev.metaclaude.spike

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import dev.metaclaude.MetaClaudeService
import dev.metaclaude.PluginInfo
import dev.metaclaude.cli.ClaudeCli
import dev.metaclaude.settings.ClaudeUiSettings
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * PHASE 6 SPIKE (developer tool, removed once the chat panel exists).
 * Drives `claude -p` in stream-json mode the way a chat panel would and records every line to
 * ~/.claude-meta/spike-<time>.log. Questions it answers (PLAN.md 6.1-6.5):
 *  - do prompts and events flow both ways; what event types and shapes arrive
 *  - do permission requests arrive as control_request can_use_tool, and is our allow reply accepted
 *  - do interrupt and set_permission_mode work mid-session
 *  - does --resume continue the session in a new process
 *  - does the IDE bridge attach in headless mode; is JCEF available
 * The test prompts are tiny and only touch a temp folder, so the log holds no project code.
 */
class StreamSpikeAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        object : Task.Backgroundable(project, "Claude UI: stream spike", true) {
            override fun run(indicator: ProgressIndicator) {
                val log = StreamSpike(project, indicator).run()
                NotificationGroupManager.getInstance().getNotificationGroup("Claude UI")
                    .createNotification("Stream spike finished", "Log: $log", NotificationType.INFORMATION)
                    .notify(project)
            }
        }.queue()
    }
}

private class StreamSpike(private val project: Project, private val indicator: ProgressIndicator) {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
    private val logFile: Path = Path.of(System.getProperty("user.home"), ".claude-meta", "spike-$stamp.log")
    private val events = LinkedBlockingQueue<JsonObject>()
    private var counter = 0

    private fun log(kind: String, text: String) {
        val line = if (text.length > 4000) text.take(4000) + " ...[truncated ${text.length - 4000} chars]" else text
        runCatching {
            Files.createDirectories(logFile.parent)
            Files.writeString(
                logFile, "${Instant.now()} $kind $line\n",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND,
            )
        }
    }

    /**
     * Spike v2 (v0.11.1). v1 answered 6.1, 6.2 and 6.4 (see STREAM-PROTOCOL.md); this run only covers the open points:
     *  - JCEF loads now that the optional com.intellij.modules.jcef dependency is declared
     *  - does `--ide` attach a -p session to our bridge (IDE tools in panel sessions)
     *  - which MCP servers system/init lists
     * One short turn, so it costs very little.
     */
    fun run(): Path {
        val app = com.intellij.openapi.application.ApplicationInfo.getInstance()
        log("INFO", "${PluginInfo.NAME} ${PluginInfo.version} stream spike v2 on ${app.fullApplicationName} (build ${app.build.asString()})")
        log("CHECK", "JCEF supported: ${runCatching { JBCefApp.isSupported() }.getOrElse { "error: ${it::class.java.simpleName}: ${it.message}" }}")

        val exe = ClaudeCli.resolve(ClaudeUiSettings.instance.state.claudePath)
        if (exe == null) { log("FAIL", "claude not found"); return logFile }
        val root = Path.of(project.basePath ?: return logFile).toRealPath()
        val service = project.service<MetaClaudeService>()
        val port = service.bridgePortForTerminal()
        log("INFO", "cwd=$root bridgePort=$port connectionsBefore=${service.bridgeConnectionCount()}")

        step("session with --ide")
        val s = start(exe, root, port, resume = null, extraArgs = listOf("--ide")) ?: return logFile
        try {
            s.send(control("initialize", JsonObject().apply { add("hooks", null) }))
            awaitControlResponse(20)
            Thread.sleep(3000)
            log("CHECK", "IDE bridge connections 3 s after start: ${service.bridgeConnectionCount()}")
            s.send(user("Reply with the single word PONG and nothing else."))
            awaitResult(120)
            log("CHECK", "IDE bridge connections after one turn: ${service.bridgeConnectionCount()}")
        } finally {
            s.close()
        }
        log("DONE", "spike v2 finished")
        return logFile
    }

    // ---------- process plumbing ----------

    private inner class Session(val proc: Process, private val stdin: BufferedWriter) {
        @Synchronized fun send(json: JsonObject) {
            val line = json.toString()
            log("IN ", line)
            stdin.write(line); stdin.write("\n"); stdin.flush() // one JSON object per line, \n only
        }

        fun close() {
            runCatching { stdin.close() }
            if (!proc.waitFor(20, TimeUnit.SECONDS)) {
                log("INFO", "process still running 20 s after closing stdin; destroying")
                proc.destroyForcibly()
            }
            log("INFO", "process exit code: ${runCatching { proc.exitValue() }.getOrDefault(-1)}")
        }
    }

    private fun start(exe: String, cwd: Path, port: Int?, resume: String?, extraArgs: List<String> = emptyList()): Session? {
        val cmd = mutableListOf(
            exe, "-p",
            "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
            "--include-partial-messages",
            "--permission-mode", "default", // the user's default is auto mode, which would never ask
            "--permission-prompt-tool", "stdio",
        )
        if (resume != null) cmd += listOf("--resume", resume)
        cmd += extraArgs
        log("INFO", "start: ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd).directory(cwd.toFile())
        if (port != null) {
            pb.environment()["CLAUDE_CODE_SSE_PORT"] = port.toString()
            pb.environment()["ENABLE_IDE_INTEGRATION"] = "true"
        }
        val proc = try { pb.start() } catch (e: Exception) { log("FAIL", "start failed: ${e.message}"); return null }
        val stdin = proc.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        val session = Session(proc, stdin)
        events.clear()

        thread(name = "spike-stdout", isDaemon = true) {
            proc.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                val obj = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                if (obj?.get("type")?.asString == "stream_event") log("OUT", compactStreamEvent(obj)) else log("OUT", line)
                if (obj != null) {
                    if (obj.get("type")?.asString == "system" && obj.get("subtype")?.asString == "init") logInit(obj)
                    if (obj.get("type")?.asString == "control_request") answerControlRequest(session, obj)
                    events.put(obj)
                }
            }
            log("INFO", "stdout closed")
        }
        thread(name = "spike-stderr", isDaemon = true) {
            proc.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { log("ERR", it) }
        }
        return session
    }

    /** The CLI asks us something (permission). Allow, echoing the input as updatedInput (required by CLI 2.1). */
    private fun answerControlRequest(session: Session, msg: JsonObject) {
        val id = msg.get("request_id")?.asString
        val request = msg.getAsJsonObject("request")
        val subtype = request?.get("subtype")?.asString
        log("CHECK", "control_request from CLI: subtype=$subtype id=$id")
        val payload = if (subtype == "can_use_tool") JsonObject().apply {
            addProperty("behavior", "allow")
            add("updatedInput", request?.get("input") ?: JsonObject())
        } else JsonObject()
        session.send(JsonObject().apply {
            addProperty("type", "control_response")
            add("response", JsonObject().apply {
                addProperty("subtype", "success")
                addProperty("request_id", id)
                add("response", payload)
            })
        })
    }

    /** Names and status only: the full init line is too long for the log. */
    private fun logInit(obj: JsonObject) {
        val servers = obj.getAsJsonArray("mcp_servers")?.joinToString { e ->
            val o = e.asJsonObject
            "${o.get("name")?.asString}:${o.get("status")?.asString}"
        }
        log("CHECK", "init: model=${obj.get("model")?.asString} permissionMode=${obj.get("permissionMode")?.asString} " +
            "keys=${obj.keySet()} mcp_servers=[$servers]")
    }

    /** Partial-message events are many; keep their shape, drop long text. */
    private fun compactStreamEvent(obj: JsonObject): String {
        val event = obj.getAsJsonObject("event")
        val delta = event?.getAsJsonObject("delta")
        return "stream_event event.type=${event?.get("type")?.asString} delta.type=${delta?.get("type")?.asString} " +
            "keys=${obj.keySet()} eventKeys=${event?.keySet()}"
    }

    // ---------- messages ----------

    private fun user(text: String) = JsonObject().apply {
        addProperty("type", "user")
        add("message", JsonObject().apply { addProperty("role", "user"); addProperty("content", text) })
        add("parent_tool_use_id", null)
    }

    private fun control(subtype: String, body: JsonObject) = JsonObject().apply {
        addProperty("type", "control_request")
        addProperty("request_id", "spike_${++counter}")
        add("request", body.deepCopy().apply { addProperty("subtype", subtype) })
    }

    // ---------- waiting ----------

    private fun step(name: String) {
        indicator.text = "Stream spike: $name"
        log("STEP", name)
    }

    private fun awaitAny(seconds: Long, match: (JsonObject) -> Boolean): JsonObject? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline && !indicator.isCanceled) {
            val e = events.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (match(e)) return e
        }
        log("TIMEOUT", "nothing matched within $seconds s")
        return null
    }

    private fun awaitResult(seconds: Long) = awaitAny(seconds) { it.get("type")?.asString == "result" }
    private fun awaitControlResponse(seconds: Long) = awaitAny(seconds) { it.get("type")?.asString == "control_response" }
}
