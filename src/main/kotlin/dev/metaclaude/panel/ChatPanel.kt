package dev.metaclaude.panel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import dev.metaclaude.protocol.Permissions
import dev.metaclaude.protocol.Checkpoints
import dev.metaclaude.protocol.ModelControls
import dev.metaclaude.protocol.UsageReport
import dev.metaclaude.protocol.Customize
import dev.metaclaude.protocol.EditProposal
import dev.metaclaude.protocol.PanelContext
import dev.metaclaude.protocol.SessionFiles
import dev.metaclaude.protocol.SessionOrganizer
import dev.metaclaude.protocol.CliPaths as CliPathsForDrop
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.metaclaude.MetaClaudeService
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import dev.metaclaude.PluginInfo
import dev.metaclaude.protocol.BridgeLog
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import com.intellij.util.concurrency.AppExecutorUtil
import dev.metaclaude.cli.ClaudeCli
import dev.metaclaude.settings.ClaudeUiSettings
import java.awt.Color
import java.awt.Cursor
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.SwingUtilities
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * The JCEF web panel. Security rules (CLAUDE.md, "Chat panel security"):
 *  1. sanitized Markdown (webview/src/markdown.ts)   2. strict CSP with a fresh nonce per load, nothing remote
 *  3. no navigation (request handler cancels it)       4. closed, validated JS → Kotlin message set (handle())
 *  6. DevTools menu item disabled. (5, native confirmation, applies once risky actions exist: Phase 8.)
 */
class ChatPanel(
    private val project: Project,
    parent: Disposable,
    /** Only the tool window's first tab may bring back the last conversation after a restart. */
    private val continueLast: Boolean = false,
    /** A session to show right away (reopened tab, history opened in a new tab). */
    private val initialResume: String? = null,
    /** Phase 11a: this tab is a fork of another conversation, from before one of its prompts. */
    private val initialFork: Fork? = null,
) : Disposable {
    /** Fork [sessionId] before prompt [prompt]: the new branch keeps the conversation up to [Checkpoints.Prompt.parentUuid]. */
    data class Fork(val sessionId: String, val prompt: Checkpoints.Prompt, val title: String)

    /** Set by ChatTabs: this panel's tab, its full title and its dot. */
    @Volatile var tab: com.intellij.ui.tabs.TabInfo? = null
    @Volatile var fullTitle: String = ""
    @Volatile var status: ChatTabs.Status = ChatTabs.Status.NONE
    private var titled = false
    private val pendingRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val tabs get() = project.service<ChatTabs>()

    /** The session this tab shows (for reopen-closed and "already open in another tab"). */
    fun sessionForReopen(): String? = currentSessionId ?: pendingResume

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) block() }
    private val browser: JBCefBrowser = JBCefBrowser.createBuilder()
        .setEnableOpenDevToolsMenuItem(false)
        .build()
    private val toKotlin: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase) // the JBCefBrowser overload is scheduled for removal
    private val diffs = PanelDiffs(project, this)
    private val plans = PlanDocuments(project, this)
    private val contextTracker = PanelContextTracker(project, this) { send(it) }
    private val paths = ProjectPaths(project)

    init {
        installFileDrop()
    }

    /**
     * Phase 9b: files dragged from the project view (or the OS) onto the panel become attachments. The page can't receive
     * IDE drags itself, so the drop lands on the Swing component and Kotlin sends the paths. Shift isn't needed here.
     */
    private fun installFileDrop() {
        val target = (browser.cefBrowser.uiComponent as? JComponent) ?: browser.component
        com.intellij.ide.dnd.DnDSupport.createBuilder(target)
            .enableAsNativeTarget()
            .setTargetChecker { e ->
                e.isDropPossible = files(e).isNotEmpty()
                true
            }
            .setDropHandler { e ->
                val dropped = files(e).take(20)
                if (dropped.isNotEmpty()) send(JsonObject().apply {
                    addProperty("type", "attachFiles")
                    add("paths", com.google.gson.JsonArray().apply { dropped.forEach { add(mentionPath(it)) } })
                })
            }
            .setDisposableParent(this)
            .install()
    }

    private fun files(e: com.intellij.ide.dnd.DnDEvent): List<java.nio.file.Path> {
        com.intellij.ide.dnd.FileCopyPasteUtil.getVirtualFileListFromAttachedObject(e.attachedObject)
            .takeIf { it.isNotEmpty() }?.let { vfs -> return vfs.mapNotNull { runCatching { it.toNioPath() }.getOrNull() } }
        // A drop from outside the IDE (Explorer): the event itself is the Transferable.
        return runCatching { com.intellij.ide.dnd.FileCopyPasteUtil.getFiles(e) }.getOrNull() ?: emptyList()
    }

    /** Project files as "src/a.rs" (like `@` mentions), others as an absolute path. */
    private fun mentionPath(p: java.nio.file.Path): String {
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(p)
        val root = vf?.let { com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).getContentRootForFile(it) }
        val rel = if (vf != null && root != null) com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(vf, root) else null
        return (rel ?: CliPathsForDrop.forCli(p.toString())) + if (vf?.isDirectory == true) "/" else ""
    }
    @Volatile private var pageLoaded = false
    @Volatile private var session: ChatSession? = null
    /** Current permission mode: the next session starts in it; the CLI reports changes (status / init). */
    @Volatile private var mode: String = initialMode()

    val component: JComponent get() = browser.component

    init {
        Disposer.register(parent, this)
        Disposer.register(this, browser)
        Disposer.register(browser, toKotlin)

        // Ctrl+O (expand/collapse thinking) and Ctrl+Alt+F (Focus view), as in VS Code. The IDE keymap sees keys before the
        // page does (v0.16.0: Ctrl+O never reached the page), so they are shortcuts local to the panel, which win over the
        // keymap while the panel has focus.
        panelShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), "toggleThinking")
        panelShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK), "toggleFocus")
        // Ctrl+Shift+T: reopen the last closed conversation tab (VS Code: Reopen Closed Session).
        panelShortcutAction(KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)) { tabs.reopenClosed() }
        // Ctrl+F4 (JetBrains' "close tab") closes this conversation tab.
        panelShortcutAction(KeyStroke.getKeyStroke(KeyEvent.VK_F4, InputEvent.CTRL_DOWN_MASK)) { tabs.close(this) }

        toKotlin.addHandler { json -> handle(json); null }
        openPanels.add(this)

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) pageLoaded = true
            }

            override fun onLoadError(
                cefBrowser: CefBrowser, frame: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String, failedUrl: String,
            ) {
                BridgeLog.log("panel load error: $errorCode $errorText", project.name)
            }
        }, browser.cefBrowser)

        // Rule 3: once our page is up, every navigation is cancelled (links go through openLink instead).
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                cefBrowser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean,
            ): Boolean {
                val block = pageLoaded || userGesture
                if (block) BridgeLog.log("panel: blocked navigation (userGesture=$userGesture)", project.name)
                return block
            }
        }, browser.cefBrowser)

        browser.loadHTML(buildHtml())
    }

    /** Rule 4: only these message types, each validated. Unknown or malformed input is logged (type only) and dropped. */
    private fun handle(json: String) {
        val msg = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
        val type = msg?.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        when (type) {
            "ready" -> sendAll(JsonObject().apply {
                addProperty("type", "hello")
                addProperty("version", PluginInfo.version)
                addProperty("ideBuild", ApplicationInfo.getInstance().build.asString())
            }, settingsMessage(), modeMessage()).also {
                ApplicationManager.getApplication().invokeLater { contextTracker.push(force = true) }
                AppExecutorUtil.getAppExecutorService().execute {
                    if (initialFork != null) showFork(initialFork)
                    else if (initialResume != null) resume(initialResume, automatic = false)
                    else if (continueLast) continueAfterReload()
                }
            }
            // Sign-in card: signing in is interactive, so it happens in a Claude terminal tab (`/login` there).
            "signIn" -> project.service<MetaClaudeService>().startSession()
            "send" -> {
                val text = msg.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                if (text.isNullOrEmpty() || text.length > MAX_PROMPT) {
                    BridgeLog.log("panel: refused send (empty or over $MAX_PROMPT chars)", project.name)
                } else {
                    if (!titled) onEdt { titled = true; tabs.setTitle(this, text) } // a new chat is named after its first prompt
                    // The page says which chips are still on (the user can drop them with X); Kotlin reads the context itself.
                    // Phase 11a: the page names each prompt (its restore point); anything that isn't a UUID gets a fresh one.
                    val uuid = msg.get("uuid")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { Checkpoints.isUuid(it) }
                        ?: java.util.UUID.randomUUID().toString()
                    turnRunning = true
                    val withFile = msg.get("includeFile")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    val withSelection = msg.get("includeSelection")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    // Rule 4: pasted images are checked (type, base64, size, count) before they reach the CLI.
                    val images = (msg.get("images") as? com.google.gson.JsonArray)?.take(PanelContext.IMAGES_MAX)?.mapNotNull { e ->
                        val o = e as? JsonObject ?: return@mapNotNull null
                        PanelContext.image(o.get("mediaType")?.takeIf { it.isJsonPrimitive }?.asString, o.get("data")?.takeIf { it.isJsonPrimitive }?.asString)
                    } ?: emptyList()
                    AppExecutorUtil.getAppExecutorService().execute {
                        saveAllIfAutosave() // Claude should read what the user sees
                        var snap: PanelContextTracker.Snapshot? = null
                        var terminals = emptyList<Pair<String, String>>()
                        ApplicationManager.getApplication().invokeAndWait {
                            if (withFile || withSelection) snap = contextTracker.snapshot()
                            val mentioned = PanelContext.terminalMentions(text)
                            terminals = mentioned.mapNotNull { t -> TerminalBuffers.text(project, t)?.let { t to it } }
                            // Say so instead of sending the mention without its output (v0.21.0 dropped it silently).
                            mentioned.filter { t -> terminals.none { it.first == t } }.forEach { t ->
                                send(JsonObject().apply {
                                    addProperty("type", "notice")
                                    addProperty("text", "Couldn't read the terminal tab \"$t\": Claude gets the mention without its output.")
                                })
                            }
                        }
                        val content = PanelContext.content(
                            text,
                            snap?.openFile?.takeIf { withFile },
                            snap?.selection?.takeIf { withSelection },
                            terminals,
                            images,
                        )
                        sessionOrStart()?.sendContent(content, uuid) ?: run { turnRunning = false }
                    }
                }
            }
            "interrupt" -> session?.interrupt()
            "newSession" -> {
                session?.let { Disposer.dispose(it) }
                session = null
                clearTasks()
                pendingResume = null
                pendingFork = null
                turnRunning = false
                currentSessionId = null
                mode = initialMode()
                titled = false
                onEdt { tabs.setTitle(this, ChatTabs.NEW_TITLE); tabs.setStatus(this, ChatTabs.Status.NONE) }
                send(modeMessage())
                send(JsonObject().apply { addProperty("type", "sessionReset") })
            }
            "openLink" -> {
                val url = msg.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                if (url != null && isSafeExternalUrl(url)) BrowserUtil.browse(url)
                else BridgeLog.log("panel: refused openLink (not an http/https URL)", project.name)
            }
            // Copy a response: the page has no clipboard access of its own; Kotlin puts plain text on the IDE clipboard.
            "copy" -> {
                val text = msg.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                if (text.isNullOrEmpty() || text.length > MAX_COPY) BridgeLog.log("panel: refused copy (empty or over $MAX_COPY chars)", project.name)
                else CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
            // Hand cursor over links. JCEF never reported cursor changes here (no onCursorChange call in the v0.14.1
            // log), so the page says when the mouse is over something clickable. Only these two values are accepted.
            "cursor" -> {
                val hand = when (msg.get("shape")?.takeIf { it.isJsonPrimitive }?.asString) {
                    "pointer" -> true
                    "default" -> false
                    else -> return
                }
                SwingUtilities.invokeLater {
                    val cursor = Cursor.getPredefinedCursor(if (hand) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
                    browser.component.cursor = cursor
                    browser.cefBrowser.uiComponent?.cursor = cursor
                }
            }
            "permissionAnswer" -> permissionAnswer(msg)
            "historyList" -> {
                val q = msg.get("query")?.takeIf { it.isJsonPrimitive }?.asString?.take(200) ?: ""
                val filter = runCatching { SessionOrganizer.Filter.valueOf(msg.get("filter")?.asString ?: "ACTIVE") }.getOrDefault(SessionOrganizer.Filter.ACTIVE)
                AppExecutorUtil.getAppExecutorService().execute { sendHistory(q, filter) }
            }
            "sessionAction" -> sessionAction(msg)
            "groupAction" -> groupAction(msg)
            "resume" -> msg.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString?.let { id ->
                AppExecutorUtil.getAppExecutorService().execute { resume(id, automatic = false) }
            }
            "export" -> {
                val toFile = msg.get("mode")?.takeIf { it.isJsonPrimitive }?.asString == "file"
                AppExecutorUtil.getAppExecutorService().execute { export(toFile) }
            }
            "mentionQuery" -> {
                val q = msg.get("query")?.takeIf { it.isJsonPrimitive }?.asString?.take(200) ?: return
                val seq = msg.get("seq")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                AppExecutorUtil.getAppExecutorService().execute {
                    var terminals = emptyList<String>()
                    ApplicationManager.getApplication().invokeAndWait { terminals = TerminalBuffers.tokens(project).map { "terminal:$it" } }
                    val found = runCatching { paths.search(q, terminals) }.getOrDefault(emptyList())
                    send(JsonObject().apply {
                        addProperty("type", "mentionResults")
                        addProperty("seq", seq)
                        add("items", com.google.gson.JsonArray().apply { found.forEach { add(it) } })
                    })
                }
            }
            "openDiff" -> msg.get("requestId")?.takeIf { it.isJsonPrimitive }?.asString?.let { id ->
                ApplicationManager.getApplication().invokeLater { openDiff(id, focus = true) }
            }
            "openPlan" -> msg.get("requestId")?.takeIf { it.isJsonPrimitive }?.asString?.let { id ->
                ApplicationManager.getApplication().invokeLater { openPlan(id) }
            }
            "setMode" -> setMode(msg.get("mode")?.takeIf { it.isJsonPrimitive }?.asString)
            "checkpoint" -> checkpoint(msg)
            "taskAction" -> taskAction(msg)
            "modelMenu" -> AppExecutorUtil.getAppExecutorService().execute { sessionOrStart()?.let { refreshModelState(it) } }
            "modelSet" -> modelSet(msg)
            "usage" -> AppExecutorUtil.getAppExecutorService().execute { usage() }
            "usageFix" -> usageFix(msg.get("period")?.takeIf { it.isJsonPrimitive }?.asString)
            "status" -> AppExecutorUtil.getAppExecutorService().execute { status() }
            "signOut" -> ApplicationManager.getApplication().invokeLater { signOut() }
            "reportProblem" -> reportProblem(msg.get("kind")?.takeIf { it.isJsonPrimitive }?.asString)
            "customize" -> AppExecutorUtil.getAppExecutorService().execute { customizeData() }
            "skillState" -> skillState(msg.get("name")?.takeIf { it.isJsonPrimitive }?.asString, msg.get("state")?.takeIf { it.isJsonPrimitive }?.asString)
            "outputStyle" -> outputStyle(msg.get("style")?.takeIf { it.isJsonPrimitive }?.asString)
            "openInstructions" -> msg.get("index")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }?.let { openInstructions(it) }
            "customStyle" -> send(JsonObject().apply { addProperty("type", "insertText"); addProperty("text", Customize.CUSTOM_STYLE_PROMPT) })
            else -> BridgeLog.log("panel: dropped unknown message type '${type?.take(40)}'", project.name)
        }
    }

    /** Starts the claude process on first use. Background thread. */
    @Synchronized
    private fun sessionOrStart(): ChatSession? {
        session?.let { return it }
        val exe = ClaudeCli.resolve(ClaudeUiSettings.instance.state.claudePath)
        val root = project.basePath?.let { Path.of(it) }
        if (exe == null || root == null) {
            send(JsonObject().apply {
                addProperty("type", "error")
                addProperty("message", "Claude CLI not found. Set its path in Settings > Tools > Clawed UI.")
            })
            return null
        }
        return try {
            ChatSession(project, exe, root.toRealPath(), this, todoTools = ClaudeUiSettings.instance.state.todoTools,
                thinkingSummaries = ClaudeUiSettings.instance.state.thinkingSummaries,
                permissionMode = mode, allowBypass = bypassAllowed(),
                resumeId = (pendingFork?.sessionId ?: pendingResume).also { pendingResume = null },
                forkAt = pendingFork?.prompt?.parentUuid.also { pendingFork = null },
                onPanelMessage = { onSessionMessage(it) }, onExit = { code ->
                session = null
                turnRunning = false
                clearTasks()
                send(JsonObject().apply { addProperty("type", "agentTasksGone") }) // they ended with the process
                send(JsonObject().apply {
                    addProperty("type", "error")
                    addProperty("message", "Claude stopped (exit code $code). Your next message starts a new session.")
                })
            }).also { s ->
                session = s
                // Phase 13a: the `/` menu gets each command's description once the CLI has said hello.
                s.whenInitialized { info ->
                    (info?.get("commands") as? com.google.gson.JsonArray)?.let { cmds ->
                        send(JsonObject().apply { addProperty("type", "commandsChanged"); add("commands", Customize.commands(cmds)) })
                    }
                }
            }
        } catch (e: Exception) {
            BridgeLog.log("panel: session start failed: ${e::class.java.name}: ${e.message}", project.name)
            send(JsonObject().apply { addProperty("type", "error"); addProperty("message", "Could not start Claude: ${e.message}") })
            null
        }
    }

    // ---------- sessions (Phase 10) ----------

    @Volatile private var currentSessionId: String? = null
    /** A session chosen from the history: shown at once, its process starts (with --resume) on the next message. */
    @Volatile private var pendingResume: String? = null
    private val history: SessionHistory? by lazy { project.basePath?.let { SessionHistory(Path.of(it), "${project.name}/panel") } }
    private val props get() = com.intellij.ide.util.PropertiesComponent.getInstance(project)

    private fun rememberSession(id: String) {
        currentSessionId = id
        props.setValue(LAST_SESSION_KEY, id)
        props.setValue(LAST_ACTIVE_KEY, System.currentTimeMillis().toString())
    }

    /** Background thread. */
    private fun sendHistory(query: String, filter: SessionOrganizer.Filter = lastFilter) {
        lastFilter = filter
        lastQuery = query
        val sessions = runCatching { history?.search(query) ?: emptyList() }.getOrDefault(emptyList())
        val current = currentSessionId ?: pendingResume
        var openIds = emptySet<String>()
        ApplicationManager.getApplication().invokeAndWait { openIds = tabs.openSessions() }
        val notes = project.service<SessionNotes>().notes()
        val items = SessionOrganizer.view(sessions, notes, openIds, filter, System.currentTimeMillis(), ClaudeUiSettings.instance.state.archiveInactiveSessions)
        send(JsonObject().apply {
            addProperty("type", "historyItems")
            addProperty("filter", filter.name)
            add("groups", com.google.gson.JsonArray().apply { notes.groups.forEach { add(it) } })
            add("items", com.google.gson.JsonArray().apply {
                items.take(300).forEach { s ->
                    add(JsonObject().apply {
                        addProperty("id", s.id)
                        addProperty("title", s.title)
                        addProperty("modified", s.modified)
                        addProperty("prompts", s.prompts)
                        addProperty("current", s.id == current)
                        addProperty("open", s.open)
                        addProperty("archived", s.archived)
                        addProperty("autoArchived", s.autoArchived)
                        s.group?.let { addProperty("group", it) }
                    })
                }
            })
        })
    }

    @Volatile private var lastFilter = SessionOrganizer.Filter.ACTIVE
    @Volatile private var lastQuery = ""

    /** Rename / archive / unarchive / move to group. Rule 4: only this project's sessions, only known groups, bounded names. */
    private fun sessionAction(msg: JsonObject) {
        val ids = ((msg.get("ids") as? com.google.gson.JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            ?: listOfNotNull(msg.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString)).take(300)
        val action = msg.get("action")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val value = msg.get("value")?.takeIf { it.isJsonPrimitive }?.asString
        AppExecutorUtil.getAppExecutorService().execute {
            val h = history ?: return@execute
            val notes = project.service<SessionNotes>()
            ids.filter { h.exists(it) }.forEach { id ->
                when (action) {
                    "rename" -> {
                        val title = SessionOrganizer.cleanName(value)
                        notes.rename(id, title)
                        onEdt { tabs.open(id)?.let { p -> tabs.setTitle(p, title ?: (h.read(id) { SessionFiles.summarize(id, it, 0L)?.title } ?: ChatTabs.NEW_TITLE)) } }
                    }
                    "archive" -> notes.setArchived(id, true)
                    "unarchive" -> notes.setArchived(id, false)
                    "setGroup" -> notes.setGroup(id, SessionOrganizer.cleanName(value))
                }
            }
            sendHistory(lastQuery)
        }
    }

    private fun groupAction(msg: JsonObject) {
        val action = msg.get("action")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val name = SessionOrganizer.cleanName(msg.get("name")?.takeIf { it.isJsonPrimitive }?.asString) ?: return
        val newName = SessionOrganizer.cleanName(msg.get("newName")?.takeIf { it.isJsonPrimitive }?.asString)
        val notes = project.service<SessionNotes>()
        when (action) {
            "create" -> notes.createGroup(name)
            "rename" -> newName?.let { notes.renameGroup(name, it) }
            "delete" -> notes.deleteGroup(name)
            else -> return
        }
        AppExecutorUtil.getAppExecutorService().execute { sendHistory(lastQuery) }
    }

    /** Background thread. Rule 4: only an id that is one of this project's session files. */
    private fun resume(id: String, automatic: Boolean) {
        val h = history ?: return
        if (!h.exists(id)) return BridgeLog.log("panel: refused resume (no such session here)", project.name)
        // Already open in another tab: go there instead of opening it twice (VS Code's "switch to its tab").
        var elsewhere = false
        ApplicationManager.getApplication().invokeAndWait {
            tabs.open(id)?.takeIf { it !== this }?.let { other -> elsewhere = true; tabs.select(other) }
        }
        if (elsewhere) return
        if (id == currentSessionId && session != null) return
        val transcript = h.read(id) { SessionFiles.transcript(it) } ?: return
        val title = project.service<SessionNotes>().notes().titles[id] ?: h.read(id) { SessionFiles.summarize(id, it, 0L)?.title } ?: id
        session?.let { s -> ApplicationManager.getApplication().invokeAndWait { Disposer.dispose(s) } }
        session = null
        clearTasks()
        pendingResume = id
        currentSessionId = id
        props.setValue(LAST_SESSION_KEY, id)
        BridgeLog.log("panel: resumed session ${if (automatic) "after reload" else "from history"} (${transcript.size} items)", project.name)
        send(JsonObject().apply { addProperty("type", "sessionReset") })
        send(JsonObject().apply { addProperty("type", "historyLoaded"); addProperty("title", title); addProperty("automatic", automatic) })
        onEdt { titled = true; tabs.setTitle(this, title) }
        transcript.forEach { send(it) }
        send(JsonObject().apply { addProperty("type", "historyEnd") })
    }

    /** Background thread: VS Code reopens the sidebar conversation if it was active in the last 10 minutes. */
    private fun continueAfterReload() {
        if (!ClaudeUiSettings.instance.state.continueAfterReload || session != null || currentSessionId != null) return
        val id = props.getValue(LAST_SESSION_KEY) ?: return
        val active = props.getValue(LAST_ACTIVE_KEY)?.toLongOrNull() ?: return
        if (System.currentTimeMillis() - active > 10 * 60_000L) return
        resume(id, automatic = true)
    }

    /** Background thread. `/export` saves the conversation as Markdown (or `/export copy` copies it). */
    private fun export(toFile: Boolean) {
        val id = currentSessionId ?: pendingResume
        val h = history
        val lines = if (id != null && h != null) h.read(id) { it.toList() } else null
        val md = lines?.let {
            val title = SessionFiles.summarize(id!!, it.asSequence(), 0L)?.title ?: "Claude conversation"
            title to SessionFiles.markdown(title, it.asSequence())
        }
        if (md == null) return send(JsonObject().apply { addProperty("type", "notice"); addProperty("text", "Nothing to export yet.") })
        val (title, text) = md
        ApplicationManager.getApplication().invokeLater {
            if (!toFile) {
                CopyPasteManager.getInstance().setContents(StringSelection(text))
                send(JsonObject().apply { addProperty("type", "notice"); addProperty("text", "Conversation copied as Markdown.") })
                return@invokeLater
            }
            val name = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(60).ifEmpty { "conversation" } + ".md"
            val dialog = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance().createSaveFileDialog(
                com.intellij.openapi.fileChooser.FileSaverDescriptor("Export Conversation", "Save the conversation as Markdown", "md"), project,
            )
            val target = dialog.save(project.basePath?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it) }, name) ?: return@invokeLater
            runCatching { java.nio.file.Files.writeString(target.file.toPath(), text) }
                .onSuccess { send(JsonObject().apply { addProperty("type", "notice"); addProperty("text", "Saved to ${target.file.name}.") }) }
                .onFailure { send(JsonObject().apply { addProperty("type", "notice"); addProperty("text", "Couldn't save: ${it.message}") }) }
        }
    }

    // ---------- Customize: commands, skills, output style, instructions (Phase 13a) ----------

    /** The last `get_skills_dialog` answer and output styles: the page may only pick from these (rule 4). */
    @Volatile private var lastSkills: JsonObject? = null
    @Volatile private var styles: List<String> = emptyList()
    @Volatile private var currentStyle: String? = null

    private fun instructionFiles(): List<Customize.Instructions> {
        val root = project.basePath ?: return emptyList()
        val sep = java.io.File.separator
        return Customize.instructionFiles(System.getProperty("user.home"), Path.of(root).toString(), sep)
    }

    /** Background thread. Everything the Customize view shows; starts the process if none is running. */
    private fun customizeData() {
        val s = sessionOrStart() ?: return
        s.whenInitialized { info -> s.reloadOutputStyles { reloaded, _ -> // picks up style files written since the start
            styles = Customize.styleNames((reloaded ?: info)?.get("available_output_styles") as? com.google.gson.JsonArray)
            if (currentStyle == null) currentStyle = info?.get("output_style")?.takeIf { it.isJsonPrimitive }?.asString?.take(100)
            s.skillsDialog { skills, err ->
                if (err != null) BridgeLog.log("panel: get_skills_dialog failed: ${err.take(200)}", project.name)
                lastSkills = skills
                send(JsonObject().apply {
                    addProperty("type", "customizeData")
                    add("commands", Customize.commands(info?.get("commands") as? com.google.gson.JsonArray))
                    add("skills", Customize.skills(skills))
                    err?.let { addProperty("skillsError", it.take(200)) }
                    add("outputStyle", JsonObject().apply {
                        currentStyle?.let { addProperty("current", it) }
                        add("available", com.google.gson.JsonArray().apply { styles.forEach { add(it) } })
                    })
                    add("instructions", com.google.gson.JsonArray().apply {
                        instructionFiles().forEach { f ->
                            add(JsonObject().apply {
                                addProperty("label", f.label)
                                addProperty("path", f.path)
                                addProperty("hint", f.hint)
                                addProperty("exists", java.io.File(f.path).isFile)
                            })
                        }
                    })
                })
            }
        } }
    }

    /** A skill's state through the CLI's own writer (`claude edit-skill-overrides --json`), then the session reloads skills. */
    private fun skillState(name: String?, state: String?) {
        val edit = Customize.skillEdit(lastSkills, name, state) ?: return BridgeLog.log("panel: refused skill state (unknown, locked or bad state)", project.name)
        AppExecutorUtil.getAppExecutorService().execute {
            val exe = ClaudeCli.resolve(ClaudeUiSettings.instance.state.claudePath) ?: return@execute notice("Claude CLI not found.")
            val root = project.basePath ?: return@execute
            val result = runCatching {
                val p = ProcessBuilder(exe, "edit-skill-overrides", "--json").directory(java.io.File(root)).redirectErrorStream(true).start()
                p.outputStream.use { it.write(edit.toString().toByteArray(Charsets.UTF_8)) }
                val out = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
                p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                p.exitValue() to out
            }
            val (code, out) = result.getOrDefault(-1 to "")
            BridgeLog.log("panel: skill state change ${if (code == 0) "done" else "failed (exit $code)"}", project.name)
            if (code != 0) {
                val reason = out.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.take(300) ?: "unknown error"
                notice("Couldn't change the skill: $reason")
            }
            val s = session ?: return@execute customizeData()
            s.reloadSkills { _, _ -> customizeData() }
        }
    }

    private fun outputStyle(style: String?) {
        val body = Customize.outputStyleRequest(style, styles) ?: return BridgeLog.log("panel: refused output style", project.name)
        val s = session ?: return
        s.updateSettings(body) { _, err ->
            if (err != null) notice("Couldn't change the output style: ${err.take(200)}")
            else { currentStyle = style; notice("Output style: $style. It applies from your next message (saved in .claude/settings.local.json).") }
            send(JsonObject().apply { addProperty("type", "outputStyleChanged"); addProperty("style", if (err == null) style else null) })
        }
    }

    /** Opens one of the listed CLAUDE.md files, creating it (with a heading) if it doesn't exist yet. */
    private fun openInstructions(index: Int) {
        val f = instructionFiles().getOrNull(index) ?: return
        ApplicationManager.getApplication().invokeLater {
            val file = java.io.File(f.path)
            if (!file.isFile) {
                val ok = Messages.showYesNoDialog(project, "Create ${f.path}?\n\n${f.hint}.", "Create Instructions File", "Create", Messages.getCancelButton(), Messages.getQuestionIcon()) == Messages.YES
                if (!ok) return@invokeLater
                runCatching { file.parentFile?.mkdirs(); file.writeText(Customize.NEW_FILE_TEXT) }
                    .onFailure { return@invokeLater notice("Couldn't create ${f.path}: ${it.message}") }
            }
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return@invokeLater
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
            AppExecutorUtil.getAppExecutorService().execute { customizeData() }
        }
    }

    // ---------- usage, status, sign out, report a problem (Phase 12b) ----------

    /** The last `get_usage` answer: "Fix" sends only the messages the CLI itself proposed there (rule 4). */
    @Volatile private var lastUsage: JsonObject? = null

    /** Background thread. `/usage`: plan limits, this session, and what used them (day / week). */
    private fun usage() {
        val s = sessionOrStart() ?: return
        send(JsonObject().apply { addProperty("type", "usageLoading") })
        s.getUsage { payload, err ->
            lastUsage = payload
            if (err != null) BridgeLog.log("panel: get_usage failed: ${err.take(200)}", project.name)
            send(UsageReport.usage(payload).apply { err?.let { addProperty("error", it.take(300)) } })
        }
    }

    /** "Fix": the CLI's own messages for that period go out as your next messages, in order. */
    private fun usageFix(period: String?) {
        if (period !in UsageReport.PERIODS) return
        val messages = UsageReport.fixMessages(lastUsage, period!!)
        if (messages.isEmpty()) return BridgeLog.log("panel: refused usage fix (nothing to fix)", project.name)
        BridgeLog.log("panel: usage fix (${messages.size} message(s))", project.name)
        send(JsonObject().apply {
            addProperty("type", "queueMessages")
            add("texts", com.google.gson.JsonArray().apply { messages.forEach { add(it) } })
        })
    }

    /** Background thread. `/status`: versions, account, model, MCP servers. */
    private fun status() {
        val s = sessionOrStart() ?: return
        val ide = ApplicationInfo.getInstance().build.asString()
        s.binaryVersion { version, _ ->
            s.getSettings { settings, _ ->
                s.mcpStatus { mcp, _ ->
                    send(UsageReport.status(PluginInfo.version, ide, s.account, version, settings, mcp, project.basePath))
                }
            }
        }
    }

    /** EDT. Native confirmation (rule 5), then `claude auth logout`; the session stops, the next message asks to sign in. */
    private fun signOut() {
        val yes = Messages.showYesNoDialog(
            project, "Sign out of Claude Code on this computer?\n\nThis signs out every Claude Code session (terminal too). " +
                "To sign in again, use the sign-in card or run /login in a Claude terminal tab.",
            "Sign Out", "Sign Out", Messages.getCancelButton(), Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!yes) return
        AppExecutorUtil.getAppExecutorService().execute {
            val exe = ClaudeCli.resolve(ClaudeUiSettings.instance.state.claudePath) ?: return@execute notice("Claude CLI not found.")
            session?.let { s -> ApplicationManager.getApplication().invokeAndWait { Disposer.dispose(s) } }
            session = null
            clearTasks()
            val ok = runCatching {
                val p = ProcessBuilder(exe, "auth", "logout").redirectErrorStream(true).start()
                p.inputStream.readAllBytes() // nothing logged: it may name the account
                p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
            }.getOrDefault(false)
            BridgeLog.log("panel: sign out ${if (ok) "done" else "failed"}", project.name)
            notice(if (ok) "Signed out. Your next message shows the sign-in card." else "Sign out didn't work: run `claude auth logout` in a terminal.")
        }
    }

    /**
     * "Report a problem": Claude Code's own `/bug` is interactive, so it runs in a Claude terminal tab; a problem with this
     * plugin opens a new GitHub issue with the versions filled in (no conversation text).
     */
    private fun reportProblem(kind: String?) {
        when (kind) {
            "claude" -> {
                project.service<MetaClaudeService>().startSession()
                notice("A Claude terminal tab is opening: type /bug there to send a report to Anthropic. It asks before it includes a transcript.")
            }
            "plugin" -> {
                val body = "**What happened**\n\n\n**What you expected**\n\n\n---\nClawed UI ${PluginInfo.version}, " +
                    "${ApplicationInfo.getInstance().fullApplicationName} (${ApplicationInfo.getInstance().build.asString()}), " +
                    "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
                val q = { v: String -> java.net.URLEncoder.encode(v, Charsets.UTF_8) }
                BrowserUtil.browse("$ISSUES_URL?title=${q("[Clawed UI] ")}&body=${q(body)}")
            }
            else -> BridgeLog.log("panel: refused reportProblem", project.name)
        }
    }

    // ---------- model, effort, thinking, context (Phase 12a) ----------

    /** Model values from the last `list_models`: the only ones the page may pick (rule 4). */
    @Volatile private var knownModels: List<String> = emptyList()
    @Volatile private var thinkingDefault = true
    /** Null until the user toggles it: then the session follows the default. */
    @Volatile private var thinkingOn: Boolean? = null

    /** Background thread: the picker's models and what is in effect now. */
    private fun refreshModelState(s: ChatSession) {
        s.listModels { modelsPayload, err ->
            if (err != null) BridgeLog.log("panel: list_models failed: ${err.take(200)}", project.name)
            val models = ModelControls.models(modelsPayload)
            knownModels = models.mapNotNull { (it as? JsonObject)?.get("value")?.asString }
            s.getSettings { settingsPayload, err2 ->
                if (err2 != null) BridgeLog.log("panel: get_settings failed: ${err2.take(200)}", project.name)
                val current = ModelControls.current(settingsPayload)
                thinkingDefault = current.get("thinkingDefault").asBoolean
                current.addProperty("thinking", thinkingOn ?: thinkingDefault)
                send(JsonObject().apply {
                    addProperty("type", "modelState")
                    add("models", models)
                    add("current", current)
                })
            }
        }
    }

    private fun requestContext(s: ChatSession) = s.contextUsage { payload, _ ->
        ModelControls.context(payload)?.let { c -> send(c.apply { addProperty("type", "contextUsage") }) }
    }

    /** Change the model, effort, Ultracode or thinking for this session. Each value is checked before it reaches the CLI. */
    private fun modelSet(msg: JsonObject) {
        val what = msg.get("what")?.takeIf { it.isJsonPrimitive }?.asString
        val value = msg.get("value")?.takeIf { it.isJsonPrimitive }
        AppExecutorUtil.getAppExecutorService().execute {
            if (turnRunning) return@execute notice("Wait until Claude finishes (or stop it) before you change the model or effort.")
            val s = sessionOrStart() ?: return@execute
            val done: (JsonObject?, String?) -> Unit = { _, err ->
                if (err != null) notice("Couldn't change ${what ?: "it"}: ${err.take(200)}")
                refreshModelState(s)
            }
            when (what) {
                "model" -> ModelControls.setModel(value?.asString, knownModels)?.let { body ->
                    BridgeLog.log("panel: model change requested", project.name)
                    s.setModel(body, done)
                } ?: BridgeLog.log("panel: refused model (not in the list)", project.name)
                "effort" -> ModelControls.setEffort(value?.asString)?.let { s.applyFlagSettings(it, done) }
                    ?: BridgeLog.log("panel: refused effort level", project.name)
                "ultracode" -> s.applyFlagSettings(ModelControls.setUltracode(value?.asBoolean == true), done)
                "thinking" -> {
                    val on = value?.asBoolean == true
                    s.setThinking(ModelControls.setThinking(on, thinkingDefault)) { p, err -> if (err == null) thinkingOn = on; done(p, err) }
                }
                else -> BridgeLog.log("panel: refused modelSet '${what?.take(20)}'", project.name)
            }
        }
    }

    // ---------- agent map (Phase 11b) ----------

    /** Tasks this session reported (id → kind) and the ones still running: the page may act only on these (rule 4). */
    private val taskKinds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val runningTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Tool calls still running that can go to the background (Bash, Agent). */
    private val backgroundable = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun clearTasks() {
        taskKinds.clear(); runningTasks.clear(); backgroundable.clear()
        currentStyle = null // a new process reports its own
        thinkingOn = null // a new process starts with the default
    }

    private fun trackTasks(type: String?, m: JsonObject) {
        fun str(k: String) = m.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        when (type) {
            "agentTaskStarted" -> str("taskId")?.let { id -> taskKinds[id] = str("kind") ?: "other"; runningTasks.add(id) }
            "agentTasksLive" -> (m.get("tasks") as? com.google.gson.JsonArray)?.forEach { t ->
                val o = t as? JsonObject ?: return@forEach
                val id = o.get("taskId")?.asString ?: return@forEach
                taskKinds.putIfAbsent(id, o.get("kind")?.asString ?: "other")
                runningTasks.add(id)
            }
            "agentTaskUpdated" -> str("status")?.takeIf { it != "running" && it != "pending" && it != "paused" }?.let { str("taskId")?.let(runningTasks::remove) }
            "agentTaskEnded" -> str("taskId")?.let(runningTasks::remove)
            "toolUse" -> if (str("name") in dev.metaclaude.protocol.AgentTasks.BACKGROUNDABLE) str("id")?.let { id ->
                backgroundable.add(id)
                m.addProperty("canBackground", true) // the page shows "Run in background" on this card only
            }
            "toolResult" -> str("toolUseId")?.let(backgroundable::remove)
            "turnEnd" -> backgroundable.clear()
        }
    }

    /** Stop a task, read a shell task's output, or send a running tool call to the background. Rule 4: known ids only. */
    private fun taskAction(msg: JsonObject) {
        val action = msg.get("action")?.takeIf { it.isJsonPrimitive }?.asString
        val id = msg.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.take(200)
        val s = session
        if (id == null || s == null) return
        when (action) {
            "stop" -> {
                if (id !in runningTasks) return BridgeLog.log("panel: refused stop (task not running)", project.name)
                BridgeLog.log("panel: stopping a ${taskKinds[id]} task", project.name)
                s.stopTask(id) { _, err -> if (err != null) notice("Couldn't stop the task: ${err.take(200)}") }
            }
            "output" -> {
                val kind = taskKinds[id] ?: return BridgeLog.log("panel: refused task output (unknown task)", project.name)
                if (!dev.metaclaude.protocol.AgentTasks.hasOutput(kind)) return
                s.taskOutput(id) { payload, err ->
                    send(dev.metaclaude.protocol.AgentTasks.output(payload).apply {
                        addProperty("type", "agentTaskOutput")
                        addProperty("taskId", id)
                        err?.let { addProperty("error", it.take(200)) }
                    })
                }
            }
            "background" -> {
                if (!backgroundable.remove(id)) return BridgeLog.log("panel: refused background (tool call not running)", project.name)
                BridgeLog.log("panel: sending a tool call to the background", project.name)
                s.backgroundTool(id) { payload, err ->
                    val moved = payload?.get("backgrounded")?.takeIf { it.isJsonPrimitive }?.asBoolean
                    if (err != null || moved == false) notice("Couldn't run it in the background${err?.let { ": ${it.take(200)}" } ?: " (it may have just finished)"}.")
                }
            }
        }
    }

    // ---------- checkpoints (Phase 11a) ----------

    /** True from a send until its turn ends: rewinding or forking mid-turn would race with Claude's own edits. */
    @Volatile private var turnRunning = false
    /** A fork chosen in another tab: its process starts (with --fork-session) on the first message. */
    @Volatile private var pendingFork: Fork? = null

    private fun notice(text: String) = send(JsonObject().apply { addProperty("type", "notice"); addProperty("text", text) })

    /**
     * Fork from here / Rewind code to here / both, from a prompt's menu. Rule 4: the action is one of three, the uuid is a
     * UUID and must be one of your prompts in this tab's own session file. Rewinding needs a native confirmation (rule 5).
     */
    private fun checkpoint(msg: JsonObject) {
        val action = msg.get("action")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it in Checkpoints.ACTIONS }
        val uuid = msg.get("uuid")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { Checkpoints.isUuid(it) }
        if (action == null || uuid == null) return BridgeLog.log("panel: refused checkpoint (bad action or id)", project.name)
        AppExecutorUtil.getAppExecutorService().execute {
            if (turnRunning) return@execute notice("Wait until Claude finishes (or stop it) before you rewind or fork.")
            val sid = currentSessionId ?: pendingResume
            val prompt = sid?.let { id -> history?.read(id) { Checkpoints.prompt(it, uuid) } }
            if (sid == null || prompt == null) return@execute notice("This message isn't saved in this conversation, so there's nothing to go back to.")
            BridgeLog.log("panel: checkpoint '$action' requested", project.name)
            when (action) {
                "fork" -> openFork(sid, prompt)
                else -> rewind(prompt) { if (action == "forkRewind") openFork(sid, prompt) }
            }
        }
    }

    /** Background thread. Dry run first, then the native confirmation listing the files, then the real rewind. */
    private fun rewind(prompt: Checkpoints.Prompt, then: () -> Unit) {
        val s = sessionOrStart() ?: return
        ApplicationManager.getApplication().invokeAndWait { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments() }
        s.rewindFiles(prompt.uuid, dryRun = true) { payload, error ->
            val preview = Checkpoints.result(payload, error)
            when {
                !preview.canRewind -> {
                    BridgeLog.log("panel: rewind not possible: ${preview.error ?: "no reason given"}", project.name)
                    notice("Can't rewind code to this message: ${preview.error ?: "it has no checkpoint (sent before checkpoints were on?)"}.")
                }
                preview.files.isEmpty() && preview.insertions + preview.deletions == 0 -> {
                    notice("No files to restore: Claude didn't change files with its edit tools after this message.")
                    then()
                }
                else -> onEdt {
                    val yes = Messages.showYesNoDialog(
                        project, Checkpoints.confirmText(preview, prompt.text, fork = false), "Rewind Code",
                        "Rewind", Messages.getCancelButton(), Messages.getWarningIcon(),
                    ) == Messages.YES
                    if (!yes) return@onEdt
                    s.rewindFiles(prompt.uuid, dryRun = false) { p2, e2 ->
                        val done = Checkpoints.result(p2, e2)
                        if (!done.canRewind) {
                            BridgeLog.log("panel: rewind failed: ${done.error}", project.name)
                            return@rewindFiles notice("Rewind failed: ${done.error ?: "unknown error"}.")
                        }
                        val files = done.files.ifEmpty { preview.files }
                        BridgeLog.log("panel: rewound ${files.size} file(s)", project.name)
                        refresh(files)
                        notice("Restored ${files.size} file${if (files.size == 1) "" else "s"} to how they were before \"${Checkpoints.short(prompt.text, 60)}\".")
                        then()
                    }
                }
            }
        }
    }

    /** The IDE sees the restored files at once (and drops the ones the rewind deleted). */
    private fun refresh(files: List<String>) {
        val root = project.basePath?.let { Path.of(it) }
        val fs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        // The folders that hold them, so files the rewind deleted disappear too.
        val dirs = files.mapNotNull { f -> runCatching { Path.of(f).let { if (it.isAbsolute || root == null) it else root.resolve(it) }.parent }.getOrNull() }
            .distinct().take(200).mapNotNull { fs.findFileByNioFile(it) }
        if (dirs.isNotEmpty()) com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(true, false, true, *dirs.toTypedArray())
    }

    /** A new tab continuing [sessionId] from before [prompt], with the prompt in the input box to edit. */
    private fun openFork(sessionId: String, prompt: Checkpoints.Prompt) {
        val title = project.service<SessionNotes>().notes().titles[sessionId] ?: history?.read(sessionId) { SessionFiles.summarize(sessionId, it, 0L)?.title } ?: "conversation"
        onEdt { tabs.newFork(Fork(sessionId, prompt, title)) }
    }

    /** Background thread, in the fork's own tab: what the branch keeps, then the prompt to edit. */
    private fun showFork(fork: Fork) {
        val kept = if (fork.prompt.parentUuid == null) emptyList()
            else history?.read(fork.sessionId) { SessionFiles.transcript(it, until = fork.prompt.uuid, withUuids = false) } ?: return
        pendingFork = fork.takeIf { it.prompt.parentUuid != null }
        BridgeLog.log("panel: fork opened (${kept.size} items kept)", project.name)
        send(JsonObject().apply { addProperty("type", "sessionReset") })
        send(JsonObject().apply { addProperty("type", "historyLoaded"); addProperty("title", fork.title); addProperty("automatic", false); addProperty("fork", true) })
        onEdt { titled = true; tabs.setTitle(this, "Fork: ${fork.title}") }
        kept.forEach { send(it) }
        send(JsonObject().apply { addProperty("type", "historyEnd") })
        send(JsonObject().apply { addProperty("type", "insertText"); addProperty("text", fork.prompt.text) })
    }

    // ---------- permissions (Phase 8) ----------

    private fun initialMode() = ClaudeUiSettings.instance.state.initialPermissionMode?.takeIf { it in Permissions.MODES } ?: "default"
    private fun bypassAllowed() = ClaudeUiSettings.instance.state.allowDangerouslySkipPermissions

    private fun modeMessage() = JsonObject().apply { addProperty("type", "permissionMode"); addProperty("mode", mode) }

    /** Keeps [mode] in step with the CLI before passing session messages on to the page. */
    private fun onSessionMessage(m: JsonObject) {
        val type = m.get("type")?.asString
        if (type == "permissionMode" || type == "sessionInfo") {
            m.get(if (type == "permissionMode") "mode" else "permissionMode")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotEmpty() }?.let { mode = it }
        }
        if (type == "sessionInfo") m.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString?.let { rememberSession(it) }
        if (type == "turnEnd") { turnRunning = false; currentSessionId?.let { rememberSession(it) } }
        trackTasks(type, m)
        if (type == "turnEnd") session?.let { requestContext(it) }
        // Tab dot: blue while a permission waits; orange when a turn ends and you aren't looking at this tab.
        when (type) {
            "permission" -> { m.get("requestId")?.asString?.let { pendingRequests.add(it) }; onEdt { tabs.setStatus(this, ChatTabs.Status.NEEDS_INPUT) } }
            "permissionResolved" -> {
                m.get("requestId")?.asString?.let { pendingRequests.remove(it) }
                if (pendingRequests.isEmpty()) onEdt { if (status == ChatTabs.Status.NEEDS_INPUT) tabs.setStatus(this, ChatTabs.Status.NONE) }
            }
            "turnEnd" -> onEdt {
                pendingRequests.clear()
                tabs.setStatus(this, if (tabs.isShowing(this)) ChatTabs.Status.NONE else ChatTabs.Status.DONE_UNSEEN)
            }
        }
        if (type == "permissionResolved") {
            val id = m.get("requestId").asString
            ApplicationManager.getApplication().invokeLater { diffs.close(id); plans.close(id) }
        }
        val diffable = type == "permission" && m.get("toolName")?.asString in EditProposal.TOOLS
        if (diffable) m.addProperty("canDiff", true)
        send(m)
        // Like VS Code: an edit waiting for approval opens in the diff viewer right away (without taking the focus).
        if (diffable) {
            val id = m.get("requestId").asString
            ApplicationManager.getApplication().invokeLater { openDiff(id, focus = false) }
        }
    }

    /** EDT. Rule 4: only for a pending Edit / MultiEdit / Write request. */
    private fun openDiff(id: String, focus: Boolean) {
        val s = session ?: return
        val (tool, input) = s.pendingRequest(id)?.takeIf { it.first in EditProposal.TOOLS } ?: return
        val path = EditProposal.filePath(input)?.let { runCatching { Path.of(it) }.getOrNull() } ?: return
        if (ClaudeUiSettings.instance.state.autosave) saveFile(path)
        val shown = diffs.open(id, tool, input, focus,
            onAccept = { edited ->
                if (edited == null) s.answer(id, true, null, null, null, null, null)
                else {
                    saveFile(path)
                    val disk = runCatching { java.nio.file.Files.readString(path) }.getOrDefault("")
                    s.allowWithInput(id, EditProposal.editedInput(tool, input, disk, edited), "Allowed with your edits")
                }
            },
            onReject = { s.denyWith(id, "The user rejected this change in the diff view.", "Rejected in the diff") },
        )
        if (!shown && focus) send(JsonObject().apply {
            addProperty("type", "notice")
            addProperty("text", "Can't show a diff for this change (the file changed since Claude read it). Use the card's buttons.")
        })
    }

    /** EDT. Rule 4: only for a pending ExitPlanMode request. */
    private fun openPlan(id: String) {
        val s = session ?: return
        val (tool, input) = s.pendingRequest(id)?.takeIf { it.first == "ExitPlanMode" } ?: return
        val plan = input.get("plan")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        plans.open(id, plan,
            onApprove = { s.answer(id, true, null, null, null, null, null) },
            onComments = { text ->
                if (text.trim() == plan.trim()) s.denyWith(id, "The user wants changes to the plan but added no comments.", "Kept planning")
                else s.denyWith(id, "The user annotated your plan: their comments and changes are inline below. Revise the plan " +
                    "accordingly and present it again.\n\n$text", "Kept planning, with your comments")
            },
        )
    }

    /** EDT. Unsaved editor changes would not be what Claude reads or edits on disk. */
    private fun saveFile(path: Path) {
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(path) ?: return
        val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        fdm.getCachedDocument(vf)?.let { if (fdm.isDocumentUnsaved(it)) fdm.saveDocument(it) }
    }

    /** Background thread. */
    private fun saveAllIfAutosave() {
        if (!ClaudeUiSettings.instance.state.autosave) return
        ApplicationManager.getApplication().invokeAndWait { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments() }
    }

    /** Rule 4: an answer only for a request that is pending, with a suggestion index and destination that exist. */
    private fun permissionAnswer(msg: JsonObject) {
        val s = session ?: return
        val id = msg.get("requestId")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        if (!s.hasPending(id)) return BridgeLog.log("panel: refused permission answer (no such pending request)", project.name)
        val allow = msg.get("decision")?.takeIf { it.isJsonPrimitive }?.asString == "allow"
        val index = msg.get("suggestion")?.takeIf { it.isJsonPrimitive }?.asInt
        val destination = msg.get("destination")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it in Permissions.DESTINATIONS }
        val message = msg.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        val answers = msg.get("answers") as? JsonObject
        val response = msg.get("response")?.takeIf { it.isJsonPrimitive }?.asString
        val suggestion = if (allow && index != null) s.suggestionOf(id, index) ?: return else null
        val effective = suggestion?.let { Permissions.withDestination(it, destination) }
        if (effective != null && Permissions.isPersistent(effective)) {
            // Rule 5: saving a permanent rule needs a native confirmation, never just a click in the web page.
            ApplicationManager.getApplication().invokeLater {
                val ok = Messages.showYesNoDialog(
                    project,
                    "${Permissions.suggestionLabel(effective)}\n\nThis is saved to ${Permissions.destinationLabel(effective.get("destination")?.asString)} " +
                        "and applies to future conversations too.",
                    "Save Permission Rule",
                    "Save and Allow", "Cancel", Messages.getWarningIcon(),
                ) == Messages.YES
                if (ok) s.answer(id, true, index, destination, null, null, null)
            }
            return
        }
        s.answer(id, allow, index, destination, message, answers, response)
    }

    /** Mode menu. Bypass only when allowed in settings, and only after a native confirmation (rule 5). */
    private fun setMode(requested: String?) {
        val target = requested?.takeIf { it in Permissions.MODES || (it == Permissions.BYPASS && bypassAllowed()) } ?: return
        val switch = {
            mode = target
            session?.setMode(target) // the CLI confirms with a status event; with no session the next one starts in it
            send(modeMessage())
        }
        if (target != Permissions.BYPASS) return switch()
        ApplicationManager.getApplication().invokeLater {
            val ok = Messages.showYesNoDialog(
                project,
                "In Bypass permissions mode Claude edits files and runs commands without asking you.\nOnly use it in a sandbox or a project you can restore.",
                "Bypass Permissions",
                "Switch to Bypass", "Cancel", Messages.getWarningIcon(),
            ) == Messages.YES
            if (ok) {
                if (session != null && session?.bypassAllowedAtStart != true) {
                    send(JsonObject().apply { addProperty("type", "notice"); addProperty("text", "Bypass applies from the next conversation: click New session.") })
                    mode = target
                    send(modeMessage())
                } else switch()
            }
        }
    }

    private fun panelShortcutAction(key: KeyStroke, action: () -> Unit) {
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = action()
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }.registerCustomShortcutSet(CustomShortcutSet(key), browser.component, this)
    }

    private fun panelShortcut(key: KeyStroke, messageType: String) {
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = send(JsonObject().apply { addProperty("type", messageType) })
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }.registerCustomShortcutSet(CustomShortcutSet(key), browser.component, this)
    }

    private fun sendAll(vararg msgs: JsonObject) = msgs.forEach { send(it) }

    private fun send(msg: JsonObject) {
        // A JSON object is a valid JS expression; Gson escapes quotes and control characters.
        browser.cefBrowser.executeJavaScript("window.__claudeUiReceive && window.__claudeUiReceive($msg);", browser.cefBrowser.url, 0)
    }

    private fun buildHtml(): String {
        val nonce = ByteArray(18).also { SecureRandom().nextBytes(it) }.let { Base64.getEncoder().encodeToString(it) }
        val js = resource("/webview/panel.js")
        val css = resource("/webview/panel.css")
        val post = toKotlin.inject("json")
        return """<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; img-src data:; font-src data:; connect-src 'none'; base-uri 'none'; form-action 'none'">
<style nonce="$nonce">$css
${themeCss()}</style></head><body><div id="app"></div>
<script nonce="$nonce">window.__claudeUiPost = function (json) { $post };</script>
<script nonce="$nonce">${js.replace("</script", "<\\/script", ignoreCase = true)}</script>
</body></html>"""
    }

    /** IDE theme colors as CSS variables (light and dark themes). */
    private fun themeCss(): String {
        fun hex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
        val font = JBUI.Fonts.label()
        return ":root { --bg: ${hex(JBColor.background())}; --fg: ${hex(JBColor.foreground())}; " +
            "--muted: ${hex(JBUI.CurrentTheme.ContextHelp.FOREGROUND)}; --border: ${hex(JBColor.border())}; " +
            "--link: ${hex(JBUI.CurrentTheme.Link.Foreground.ENABLED)}; " +
            "--font: \"${font.family.replace("\"", "")}\", system-ui, sans-serif; } " +
            "html, body { font-size: ${font.size}px; }"
    }

    private fun resource(path: String): String =
        ChatPanel::class.java.getResourceAsStream(path)?.use { it.readAllBytes().toString(Charsets.UTF_8) }
            ?: error("missing $path: the webview bundle was not packaged (run npm run build in webview/)")

    override fun dispose() {
        openPanels.remove(this)
    }

    /** Panel settings, sent with `hello` and again whenever they change (v0.17.0: reopening the tool window keeps the page). */
    private fun settingsMessage() = JsonObject().apply {
        addProperty("type", "settings")
        addProperty("showTimestamps", ClaudeUiSettings.instance.state.showMessageTimestamps)
        add("modes", com.google.gson.JsonArray().apply {
            val all = Permissions.MODES.toList() + if (bypassAllowed()) listOf(Permissions.BYPASS to Permissions.BYPASS_LABEL) else emptyList()
            all.forEach { (id, label) -> add(JsonObject().apply { addProperty("id", id); addProperty("label", label) }) }
        })
    }

    companion object {
        private const val MAX_PROMPT = 100_000
        private const val ISSUES_URL = "https://github.com/mrGlasses/ClaudeUiForRustRover/issues/new"
        private const val MAX_COPY = 2_000_000
        private const val LAST_SESSION_KEY = "clawed.ui.lastSession"
        private const val LAST_ACTIVE_KEY = "clawed.ui.lastActive"
        private val openPanels: MutableSet<ChatPanel> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /**
         * Alt+K with the chat panel showing: put "@file#5-10 " in its prompt box (like VS Code) instead of a terminal session.
         * Returns false when this project has no visible chat panel. EDT.
         */
        fun insertMention(project: Project, text: String): Boolean {
            val window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Claude Chat")
            if (window?.isVisible != true) return false
            val panel = project.service<ChatTabs>().selected() ?: openPanels.firstOrNull { it.project == project } ?: return false
            panel.send(JsonObject().apply { addProperty("type", "insertText"); addProperty("text", text) })
            window.activate(null)
            return true
        }

        /** Called after Settings > Tools > Clawed UI is applied. */
        fun applySettingsToOpenPanels() = openPanels.forEach { it.send(it.settingsMessage()) }

        fun isSafeExternalUrl(url: String): Boolean {
            if (url.length > 2048) return false
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank()
        }
    }
}
