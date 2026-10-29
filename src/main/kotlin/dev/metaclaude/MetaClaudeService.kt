package dev.metaclaude

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import dev.metaclaude.cli.ClaudeCli
import dev.metaclaude.cli.SessionCommand
import dev.metaclaude.compat.IntegrationMode
import dev.metaclaude.compat.OfficialPluginDetector
import dev.metaclaude.protocol.*
import dev.metaclaude.settings.ClaudeUiConfigurable
import dev.metaclaude.settings.ClaudeUiSettings
import dev.metaclaude.tools.*
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * One bridge (server + lock file) per project window. [scope] owns the bridge's listeners and pending diffs,
 * so stopping the bridge (mode switch) leaves nothing running.
 */
private class Bridge(val server: IdeBridgeServer, val lock: LockFile, val port: Int, val scope: Disposable) {
    @Volatile var clientConnected = false
    fun close() {
        Disposer.dispose(scope) // first: answers pending diffs while the socket is still open
        runCatching { server.stop(500) }
        runCatching { lock.close() }
    }
}

@Service(Service.Level.PROJECT)
class MetaClaudeService(private val project: Project) : Disposable {
    private val ideName = PluginInfo.IDE_NAME // distinct, so /ide shows two clearly separate entries if both plugins are present
    @Volatile private var bridge: Bridge? = null

    private fun effectiveMode() = OfficialPluginDetector.effective(ClaudeUiSettings.instance.integrationMode)

    /**
     * New Claude Session: a "Claude" tab in the Terminal tool window, already connected to our bridge. Any thread.
     * The preparation runs in the background: the shell lookup may block (forbidden on the EDT, v0.10.0 error),
     * and starting the bridge can take up to 5 s. Only opening the tab happens on the EDT.
     */
    fun startSession(forced: IntegrationMode? = null) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val root = Path.of(project.basePath ?: return@executeOnPooledThread).toRealPath()
            val effective = forced ?: effectiveMode()
            val exe = ClaudeCli.resolve(ClaudeUiSettings.instance.state.claudePath)
            if (exe == null) {
                notify(
                    "Claude CLI not found",
                    "Install Claude Code, or set its path in Settings > Tools > Claude UI.",
                    NotificationAction.createSimple("Open settings") {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeUiConfigurable::class.java)
                    },
                )
                return@executeOnPooledThread
            }
            if (effective == IntegrationMode.BUILTIN) ensureBridge(root) // before the tab opens: the terminal customizer needs the port
            val shell = TerminalProjectOptionsProvider.getInstance(project).shellPath
            val command = SessionCommand.build(exe, ClaudeCli.isOnPath(exe), shell, CliPaths.isWindows)
            app.invokeLater({
                ClaudeTerminal.open(project, root, command)
                scheduleHealthCheck(effective, root)
            }, project.disposed)
        }
    }

    /** Called at project open and when settings change. In OFFICIAL mode we deliberately stay out of the way. */
    fun startBridgeIfEnabled() {
        if (effectiveMode() != IntegrationMode.BUILTIN) return
        val root = Path.of(project.basePath ?: return).toRealPath()
        runCatching { ensureBridge(root) }
            .onFailure { BridgeLog.log("bridge start failed: ${it::class.java.name}: ${it.message}", project.name) }
    }

    /** Starts or stops the bridge to match the current integration mode. */
    fun applySettings() {
        if (effectiveMode() == IntegrationMode.BUILTIN) startBridgeIfEnabled() else stopBridge()
    }

    @Synchronized
    private fun stopBridge() {
        val b = bridge ?: return
        bridge = null
        b.close()
        BridgeLog.log("bridge stopped: integration mode is ${ClaudeUiSettings.instance.integrationMode.name}", project.name)
    }

    @Synchronized // startup activity, terminal customizer and the Start action can race
    private fun ensureBridge(root: Path): Bridge {
        bridge?.let { return it }
        BridgeLog.log("${PluginInfo.NAME} ${PluginInfo.version} starting bridge", project.name)
        LockFile.sweepStale(ideName)
        LockFile.sweepStale(LEGACY_IDE_NAME) // dead lock files from builds before the rename

        val token = AuthToken.generate()
        val scope = Disposer.newDisposable(this, "Claude UI bridge")
        val diffs = DiffCoordinator(project, scope) // answers pending diffs when the bridge stops
        val tracker = SelectionTracker(project, scope) { method, params -> bridge?.server?.broadcastNotification(method, params) }
        val registry = ToolRegistry(listOf(
            GetWorkspaceFolders(project), GetOpenEditors(project),
            GetCurrentSelection(tracker), GetLatestSelection(tracker),
            OpenFile(project), GetDiagnostics(project),
            diffs, CloseTab(diffs), CloseAllDiffTabs(diffs),
            CheckDocumentDirty(project), SaveDocument(project),
        ))
        val dispatcher = McpDispatcher(registry, onIdeConnected = { tracker.onIdeConnected() }, logTag = project.name)
        val server = IdeBridgeServer(token, dispatcher, onClientConnected = { bridge?.clientConnected = true }, logTag = project.name)
        val port = server.startAndGetPort()

        val lock = LockFile(port, LockFileContent(
            pid = ProcessHandle.current().pid(),
            workspaceFolders = listOf(root.toString()),
            ideName = ideName,
            runningInWindows = System.getProperty("os.name").startsWith("Windows"),
            authToken = token,
        ))
        lock.write()
        val b = Bridge(server, lock, port, scope)
        bridge = b
        return b
    }

    /** Port for new terminal tabs, starting the bridge if a terminal opens before the startup activity ran. */
    fun bridgePortForTerminal(): Int? {
        if (effectiveMode() != IntegrationMode.BUILTIN) return null
        startBridgeIfEnabled()
        return bridge?.port
    }

    /** Alt+K: puts @file#start-end into the connected session's prompt. False if no CLI is connected. */
    fun sendAtMention(filePath: String, lineStart: Int, lineEnd: Int): Boolean {
        val server = bridge?.server ?: return false
        if (server.connections.isEmpty()) return false
        server.broadcastNotification("at_mentioned", ContextJson.atMention(filePath, lineStart, lineEnd))
        BridgeLog.log("at_mentioned sent: 0-based lines $lineStart-$lineEnd", project.name)
        return true
    }

    /** The "avoid connection problems" part: verify the link, and if it failed offer the other path. */
    private fun scheduleHealthCheck(effective: IntegrationMode, root: Path) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            when (effective) {
                IntegrationMode.BUILTIN -> if (bridge?.server?.connections.isNullOrEmpty()) notify(
                    "Claude did not attach to the IDE bridge",
                    "Bridge listening on port ${bridge?.port}, lock file ${bridge?.lock?.path}. " +
                        "Run /ide inside the session, or test the CLI in Settings > Tools > Claude UI.",
                )
                else -> if (LockScanner.forWorkspace(root, bridge?.port).isEmpty()) notify(
                    "Official Claude plugin is not advertising this project",
                    "No live lock file in ~/.claude/ide covers this folder.",
                    NotificationAction.createSimple("Switch to built-in and restart") {
                        ClaudeUiSettings.instance.integrationMode = IntegrationMode.BUILTIN
                        applySettingsToOpenProjects()
                        startSession()
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
        bridge?.close(); bridge = null // also removes our lock file
    }

    companion object {
        private const val LEGACY_IDE_NAME = "RustRover (Claude Meta)"

        /** After a settings change: every open window starts or stops its bridge. */
        fun applySettingsToOpenProjects() {
            ProjectManager.getInstance().openProjects
                .filter { !it.isDisposed }
                .forEach { it.service<MetaClaudeService>().applySettings() }
        }
    }
}

class StartSessionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<MetaClaudeService>()?.startSession()
    }
}
