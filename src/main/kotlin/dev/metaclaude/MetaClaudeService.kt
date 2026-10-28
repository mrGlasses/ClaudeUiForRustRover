package dev.metaclaude

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.metaclaude.cli.ClaudeLauncher
import dev.metaclaude.compat.IntegrationMode
import dev.metaclaude.compat.OfficialPluginDetector
import dev.metaclaude.protocol.*
import dev.metaclaude.tools.*
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** One bridge (server + lock file) per project window. */
private class Bridge(val server: IdeBridgeServer, val lock: LockFile, val port: Int) {
    @Volatile var clientConnected = false
    fun close() { runCatching { server.stop(500) }; runCatching { lock.close() } }
}

@Service(Service.Level.PROJECT)
class MetaClaudeService(private val project: Project) : Disposable {
    private val ideName = PluginInfo.IDE_NAME // distinct, so /ide shows two clearly separate entries if both plugins are present
    @Volatile private var bridge: Bridge? = null
    @Volatile private var currentProc: Process? = null

    // TODO: move to a settings service + configurable
    var mode: IntegrationMode = IntegrationMode.BUILTIN // default BUILTIN: it is the path we control end to end
    var claudePath: String = "claude"

    fun startSession(forced: IntegrationMode? = null): Process {
        val root = Path.of(requireNotNull(project.basePath)).toRealPath()
        val effective = OfficialPluginDetector.effective(forced ?: mode)

        currentProc?.destroy() // a restart (e.g. from the health-check action) must not leave the old CLI running
        val port = if (effective == IntegrationMode.BUILTIN) ensureBridge(root).port else null
        val proc = ClaudeLauncher.spawnPty(claudePath, root, port)
        currentProc = proc
        scheduleHealthCheck(effective, root)
        return proc
    }

    /** Called at project open. In OFFICIAL mode we deliberately stay out of the way. */
    fun startBridgeIfEnabled() {
        if (mode != IntegrationMode.BUILTIN) return
        val root = Path.of(project.basePath ?: return).toRealPath()
        runCatching { ensureBridge(root) }
            .onFailure { BridgeLog.log("bridge start failed: ${it::class.java.name}: ${it.message}") }
    }

    @Synchronized // startup activity and the Start action can race
    private fun ensureBridge(root: Path): Bridge {
        bridge?.let { return it }
        BridgeLog.log("${PluginInfo.NAME} ${PluginInfo.version} starting bridge for project '${project.name}'")
        LockFile.sweepStale(ideName)
        LockFile.sweepStale(LEGACY_IDE_NAME) // dead lock files from builds before the rename

        val token = AuthToken.generate()
        lateinit var b: Bridge
        val diffs = DiffCoordinator(project, this) // disposed with the service: answers pending diffs on close
        val tracker = SelectionTracker(project, this) { method, params -> bridge?.server?.broadcastNotification(method, params) }
        val registry = ToolRegistry(listOf(
            GetWorkspaceFolders(project), GetOpenEditors(project),
            GetCurrentSelection(tracker), GetLatestSelection(tracker),
            OpenFile(project), GetDiagnostics(project),
            diffs, CloseTab(diffs), CloseAllDiffTabs(diffs),
            CheckDocumentDirty(project), SaveDocument(project),
        ))
        val dispatcher = McpDispatcher(registry, onIdeConnected = { tracker.onIdeConnected() })
        val server = IdeBridgeServer(token, dispatcher, onClientConnected = { bridge?.clientConnected = true })
        val port = server.startAndGetPort()

        val lock = LockFile(port, LockFileContent(
            pid = ProcessHandle.current().pid(),
            workspaceFolders = listOf(root.toString()),
            ideName = ideName,
            runningInWindows = System.getProperty("os.name").startsWith("Windows"),
            authToken = token,
        ))
        lock.write()
        b = Bridge(server, lock, port)
        bridge = b
        return b
    }

    /** Port for new terminal tabs, starting the bridge if a terminal opens before the startup activity ran. */
    fun bridgePortForTerminal(): Int? {
        if (mode != IntegrationMode.BUILTIN) return null
        startBridgeIfEnabled()
        return bridge?.port
    }

    /** Alt+K: puts @file#start-end into the connected session's prompt. False if no CLI is connected. */
    fun sendAtMention(filePath: String, lineStart: Int, lineEnd: Int): Boolean {
        val server = bridge?.server ?: return false
        if (server.connections.isEmpty()) return false
        server.broadcastNotification("at_mentioned", ContextJson.atMention(filePath, lineStart, lineEnd))
        BridgeLog.log("at_mentioned sent: 0-based lines $lineStart-$lineEnd")
        return true
    }

    /** The "avoid connection problems" part: verify the link, and if it failed offer the other path. */
    private fun scheduleHealthCheck(effective: IntegrationMode, root: Path) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            when (effective) {
                IntegrationMode.BUILTIN -> if (bridge?.clientConnected != true) notify(
                    "Claude did not attach to the IDE bridge",
                    "Bridge listening on port ${bridge?.port}, lock file ${bridge?.lock?.path}. " +
                        "Run /ide inside the session, or check claude --version.",
                )
                else -> if (LockScanner.forWorkspace(root, bridge?.port).isEmpty()) notify(
                    "Official Claude plugin is not advertising this project",
                    "No live lock file in ~/.claude/ide covers this folder.",
                    NotificationAction.createSimple("Use built-in bridge and restart") {
                        startSession(forced = IntegrationMode.BUILTIN)
                    },
                )
            }
        }, 8, TimeUnit.SECONDS)
    }

    private fun notify(title: String, body: String, action: NotificationAction? = null) {
        NotificationGroupManager.getInstance().getNotificationGroup("Claude UI")
            .createNotification(title, body, NotificationType.WARNING)
            .also { n -> action?.let(n::addAction) }
            .notify(project)
    }

    override fun dispose() {
        currentProc?.destroy()
        bridge?.close(); bridge = null // also removes our lock file
    }

    private companion object {
        const val LEGACY_IDE_NAME = "RustRover (Claude Meta)"
    }
}

class StartSessionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<MetaClaudeService>()?.startSession()
        // TODO: hand the PtyProcess to a terminal widget / tool window
    }
}
