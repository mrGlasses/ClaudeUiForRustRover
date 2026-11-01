package dev.metaclaude.panel

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.util.ui.JBUI
import dev.metaclaude.protocol.BridgeLog
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * Phase 10b: several conversations in the "Claude Chat" tool window, one tab per ChatPanel, each with its own
 * `claude` process. Tab dots like VS Code: blue = waiting for your permission, orange = finished while you weren't looking.
 * Closed tabs remember their session, so Ctrl+Shift+T (inside the panel) reopens the last one. EDT only.
 */
@Service(Service.Level.PROJECT)
class ChatTabs(private val project: Project) {
    enum class Status { NONE, NEEDS_INPUT, DONE_UNSEEN }

    private val closed = ArrayDeque<String>() // session ids of closed tabs, newest last
    private var tabs: JBTabs? = null

    fun toolWindow(): ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)

    /**
     * Called once by the tool window factory. v0.23.2 used the tool window's own content tabs, which can't scroll: with a few
     * long names some tabs were out of reach. Now one content holds an editor-style tab bar (JBTabs, like the editor's own
     * tabs): a single row that scrolls with the mouse wheel, a close button on each tab, and a list of the tabs that don't fit.
     */
    fun install(toolWindow: ToolWindow) {
        if (tabs != null) return
        val bar = JBTabsFactory.createEditorTabs(project, toolWindow.disposable)
        bar.presentation.setSingleRow(true)
        bar.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                val p = panelOf(newSelection) ?: return
                if (p.status == Status.DONE_UNSEEN) setStatus(p, Status.NONE)
            }
        }, toolWindow.disposable)
        tabs = bar
        val content = ContentFactory.getInstance().createContent(bar.component, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(
            object : DumbAwareAction("New Conversation", "Open a new Claude conversation in a tab", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    newTab(null)
                }
            },
            object : DumbAwareAction("Open Conversations", "Switch to any open conversation tab", AllIcons.Actions.ListFiles) {
                override fun actionPerformed(e: AnActionEvent) {
                    showSwitcher(e)
                }
            },
        ))
        if (newTab(null, continueLast = true) == null) {
            val label = com.intellij.ui.components.JBLabel("The chat panel failed to open (see bridge.log).", javax.swing.SwingConstants.CENTER)
            toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(label, "", false))
        }
    }

    /** Opens a conversation tab; [resumeId] shows that session (the next message continues it). Returns the panel. */
    fun newTab(resumeId: String?, continueLast: Boolean = false): ChatPanel? {
        val tw = toolWindow() ?: return null
        val bar = tabs ?: return null
        open(resumeId)?.let { select(it); return it } // already open: switch to it (VS Code)
        val panel = try {
            ChatPanel(project, tw.disposable, continueLast = continueLast, initialResume = resumeId)
        } catch (e: Throwable) {
            BridgeLog.log("panel failed to open: ${e::class.java.name}: ${e.message}", project.name)
            return null
        }
        val info = TabInfo(panel.component).setText(NEW_TITLE).setObject(panel)
        info.setTabLabelActions(DefaultActionGroup(object : DumbAwareAction("Close Conversation", "Close this conversation tab", AllIcons.Actions.Close) {
            override fun actionPerformed(e: AnActionEvent) {
                close(panel)
            }
        }), ActionPlaces.EDITOR_TAB)
        panel.tab = info
        bar.addTab(info)
        bar.select(info, true)
        return panel
    }

    /** Closes a tab: its process stops, its session can come back with Ctrl+Shift+T. Never leaves the window empty. */
    fun close(panel: ChatPanel) {
        val bar = tabs ?: return
        val info = panel.tab ?: return
        panel.sessionForReopen()?.let { id ->
            closed.remove(id)
            closed.addLast(id)
            while (closed.size > 20) closed.removeFirst()
        }
        bar.removeTab(info)
        Disposer.dispose(panel)
        if (bar.tabCount == 0 && !project.isDisposed) newTab(null)
    }

    /** The open tab showing [sessionId], if any. */
    fun open(sessionId: String?): ChatPanel? =
        if (sessionId == null) null else panels().firstOrNull { it.sessionForReopen() == sessionId }

    /** The tab you're looking at. */
    fun selected(): ChatPanel? = panelOf(tabs?.selectedInfo)

    /** A list of every open conversation tab (full titles, state as text), to pick one. */
    private fun showSwitcher(e: AnActionEvent) {
        val all = panels()
        if (all.isEmpty()) return
        val label = { p: ChatPanel ->
            val mark = when (p.status) { Status.NEEDS_INPUT -> "● waiting · "; Status.DONE_UNSEEN -> "● done · "; else -> "" }
            mark + (p.fullTitle.ifBlank { NEW_TITLE })
        }
        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createPopupChooserBuilder(all)
            .setTitle("Open Conversations")
            .setRenderer(object : com.intellij.ui.ColoredListCellRenderer<ChatPanel>() { // SimpleListCellRenderer.create is scheduled for removal
                override fun customizeCellRenderer(list: javax.swing.JList<out ChatPanel>, value: ChatPanel?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append(value?.let(label) ?: "")
                }
            })
            .setItemChosenCallback { p -> select(p) }
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
        panel.tab?.let { tabs?.select(it, true) }
        toolWindow()?.activate(null)
    }

    fun isShowing(panel: ChatPanel): Boolean {
        val tw = toolWindow() ?: return false
        return tw.isVisible && tabs?.selectedInfo == panel.tab
    }

    fun setStatus(panel: ChatPanel, status: Status) {
        panel.status = status
        panel.tab?.setIcon(when (status) {
            Status.NONE -> null
            Status.NEEDS_INPUT -> Dot(JBColor(0x3574F0, 0x548AF7))
            Status.DONE_UNSEEN -> Dot(JBColor(0xE08855, 0xE08855))
        })
    }

    fun setTitle(panel: ChatPanel, title: String) {
        val t = title.lineSequence().first().trim()
        panel.fullTitle = t
        panel.tab?.setText(if (t.length > 24) t.take(23) + "…" else t.ifEmpty { NEW_TITLE })
        panel.tab?.setTooltipText(com.intellij.openapi.util.text.HtmlChunk.text(t))
    }

    private fun panels(): List<ChatPanel> = tabs?.tabs?.mapNotNull { panelOf(it) } ?: emptyList()
    private fun panelOf(info: TabInfo?): ChatPanel? = info?.`object` as? ChatPanel

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
    }
}
