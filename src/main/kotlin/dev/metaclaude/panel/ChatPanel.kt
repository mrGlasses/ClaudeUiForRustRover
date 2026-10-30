package dev.metaclaude.panel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.metaclaude.MetaClaudeService
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
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
class ChatPanel(private val project: Project, parent: Disposable) : Disposable {
    private val browser: JBCefBrowser = JBCefBrowser.createBuilder()
        .setEnableOpenDevToolsMenuItem(false)
        .build()
    private val toKotlin: JBCefJSQuery = JBCefJSQuery.create(browser)
    @Volatile private var pageLoaded = false
    @Volatile private var session: ChatSession? = null

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

        toKotlin.addHandler { json -> handle(json); null }

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
            "ready" -> send(JsonObject().apply {
                addProperty("type", "hello")
                addProperty("version", PluginInfo.version)
                addProperty("ideBuild", ApplicationInfo.getInstance().build.asString())
                addProperty("showTimestamps", ClaudeUiSettings.instance.state.showMessageTimestamps)
            })
            // Sign-in card: signing in is interactive, so it happens in a Claude terminal tab (`/login` there).
            "signIn" -> project.service<MetaClaudeService>().startSession()
            "send" -> {
                val text = msg.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                if (text.isNullOrEmpty() || text.length > MAX_PROMPT) {
                    BridgeLog.log("panel: refused send (empty or over $MAX_PROMPT chars)", project.name)
                } else {
                    AppExecutorUtil.getAppExecutorService().execute { sessionOrStart()?.send(text) }
                }
            }
            "interrupt" -> session?.interrupt()
            "newSession" -> {
                session?.let { Disposer.dispose(it) }
                session = null
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
                thinkingSummaries = ClaudeUiSettings.instance.state.thinkingSummaries, onPanelMessage = { send(it) }, onExit = { code ->
                session = null
                send(JsonObject().apply {
                    addProperty("type", "error")
                    addProperty("message", "Claude stopped (exit code $code). Your next message starts a new session.")
                })
            }).also { session = it }
        } catch (e: Exception) {
            BridgeLog.log("panel: session start failed: ${e::class.java.name}: ${e.message}", project.name)
            send(JsonObject().apply { addProperty("type", "error"); addProperty("message", "Could not start Claude: ${e.message}") })
            null
        }
    }

    private fun panelShortcut(key: KeyStroke, messageType: String) {
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = send(JsonObject().apply { addProperty("type", messageType) })
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }.registerCustomShortcutSet(CustomShortcutSet(key), browser.component, this)
    }

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

    override fun dispose() = Unit

    companion object {
        private const val MAX_PROMPT = 100_000
        private const val MAX_COPY = 2_000_000

        fun isSafeExternalUrl(url: String): Boolean {
            if (url.length > 2048) return false
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank()
        }
    }
}
