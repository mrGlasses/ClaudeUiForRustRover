package dev.metaclaude.panel

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.ui.JBUI
import dev.metaclaude.protocol.BridgeLog
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * Phase 10b: several conversations in the "Claude Chat" tool window, one tab (Content) per ChatPanel, each with its own
 * `claude` process. Tab dots like VS Code: blue = waiting for your permission, orange = finished while you weren't looking.
 * Closed tabs remember their session, so Ctrl+Shift+T (inside the panel) reopens the last one. EDT only.
 */
@Service(Service.Level.PROJECT)
class ChatTabs(private val project: Project) {
    enum class Status { NONE, NEEDS_INPUT, DONE_UNSEEN }

    private val closed = ArrayDeque<String>() // session ids of closed tabs, newest last
    private var installed = false

    fun toolWindow(): ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)

    /** Called once by the tool window factory: "+" action, listeners, the first tab (which may continue the last chat). */
    fun install(toolWindow: ToolWindow) {
        if (installed) return
        installed = true
        toolWindow.setTitleActions(listOf(
            object : DumbAwareAction("New Conversation", "Open a new Claude conversation in a tab", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    newTab(null)
                }
            },
            // With several long tab names the tool window can't show every tab (v0.23.1 test: the first one was unreachable).
            object : DumbAwareAction("Open Conversations", "Switch to any open conversation tab", AllIcons.Actions.ListFiles) {
                override fun actionPerformed(e: AnActionEvent) {
                    showSwitcher(e)
                }
            },
        ))
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                val p = panelOf(event.content) ?: return
                if (event.operation == ContentManagerEvent.ContentOperation.add && p.status == Status.DONE_UNSEEN) setStatus(p, Status.NONE)
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                panelOf(event.content)?.sessionForReopen()?.let { id ->
                    closed.remove(id)
                    closed.addLast(id)
                    while (closed.size > 20) closed.removeFirst()
                }
                // Never an empty window: after the last tab closes, open a fresh one (later, and not while the project closes).
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                    runCatching { if (toolWindow.contentManager.contentCount == 0) newTab(null) }
                }, project.disposed)
            }
        })
        if (newTab(null, continueLast = true) == null) {
            val label = com.intellij.ui.components.JBLabel("The chat panel failed to open (see bridge.log).", javax.swing.SwingConstants.CENTER)
            toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(label, "", false))
        }
    }

    /** Opens a conversation tab; [resumeId] shows that session (the next message continues it). Returns the panel. */
    fun newTab(resumeId: String?, continueLast: Boolean = false): ChatPanel? {
        val tw = toolWindow() ?: return null
        open(resumeId)?.let { select(it); return it } // already open: switch to it (VS Code)
        val panel = try {
            ChatPanel(project, tw.disposable, continueLast = continueLast, initialResume = resumeId)
        } catch (e: Throwable) {
            BridgeLog.log("panel failed to open: ${e::class.java.name}: ${e.message}", project.name)
            return null
        }
        val content = ContentFactory.getInstance().createContent(panel.component, NEW_TITLE, false)
        content.isCloseable = true
        // Tool window tabs hide content icons unless asked (v0.23.0 test: the dots never showed).
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
        content.setDisposer(panel)
        content.putUserData(PANEL, panel)
        panel.content = content
        tw.contentManager.addContent(content)
        tw.contentManager.setSelectedContent(content, true)
        return panel
    }

    /** The open tab showing [sessionId], if any. */
    fun open(sessionId: String?): ChatPanel? =
        if (sessionId == null) null else panels().firstOrNull { it.sessionForReopen() == sessionId }

    /** A list of every open conversation tab (full titles, dots shown as text), to pick one. */
    private fun showSwitcher(e: AnActionEvent) {
        val tw = toolWindow() ?: return
        val contents = tw.contentManager.contents.filter { panelOf(it) != null }
        if (contents.isEmpty()) return
        val label = { c: Content ->
            val mark = when (panelOf(c)?.status) { Status.NEEDS_INPUT -> "● waiting · "; Status.DONE_UNSEEN -> "● done · "; else -> "" }
            mark + (c.description?.takeIf { it.isNotBlank() } ?: c.displayName ?: NEW_TITLE)
        }
        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createPopupChooserBuilder(contents)
            .setTitle("Open Conversations")
            .setRenderer(object : com.intellij.ui.ColoredListCellRenderer<Content>() { // SimpleListCellRenderer.create is scheduled for removal
                override fun customizeCellRenderer(list: javax.swing.JList<out Content>, value: Content?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append(value?.let(label) ?: "")
                }
            })
            .setItemChosenCallback { c -> tw.contentManager.setSelectedContent(c, true) }
            .createPopup()
        val component = e.inputEvent?.component
        if (component != null) popup.showUnderneathOf(component) else popup.showInFocusCenter()
    }

    fun reopenClosed() {
        val id = closed.removeLastOrNull() ?: return
        newTab(id)
        toolWindow()?.activate(null)
    }

    fun select(panel: ChatPanel) {
        val tw = toolWindow() ?: return
        panel.content?.let { tw.contentManager.setSelectedContent(it, true) }
        tw.activate(null)
    }

    fun isShowing(panel: ChatPanel): Boolean {
        val tw = toolWindow() ?: return false
        return tw.isVisible && tw.contentManager.selectedContent == panel.content
    }

    fun setStatus(panel: ChatPanel, status: Status) {
        panel.status = status
        panel.content?.icon = when (status) {
            Status.NONE -> null
            Status.NEEDS_INPUT -> Dot(JBColor(0x3574F0, 0x548AF7))
            Status.DONE_UNSEEN -> Dot(JBColor(0xE08855, 0xE08855))
        }
    }

    fun setTitle(panel: ChatPanel, title: String) {
        val t = title.lineSequence().first().trim()
        panel.content?.displayName = if (t.length > 18) t.take(17) + "…" else t.ifEmpty { NEW_TITLE }
        panel.content?.description = t
    }

    private fun panels(): List<ChatPanel> = toolWindow()?.contentManager?.contents?.mapNotNull { panelOf(it) } ?: emptyList()
    private fun panelOf(c: Content?): ChatPanel? = c?.getUserData(PANEL)

    /** A small filled circle for the tab icon. */
    private class Dot(private val color: JBColor) : Icon {
        override fun getIconWidth() = JBUI.scale(8)
        override fun getIconHeight() = JBUI.scale(8)
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x, y, iconWidth, iconHeight)
            g2.dispose()
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "Claude Chat"
        const val NEW_TITLE = "New chat"
        private val PANEL = com.intellij.openapi.util.Key.create<ChatPanel>("clawed.ui.chatPanel")
    }
}
