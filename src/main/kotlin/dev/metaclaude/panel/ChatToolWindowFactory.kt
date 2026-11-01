package dev.metaclaude.panel

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import javax.swing.SwingConstants

/** "Claude Chat" tool window. Registered from claude-ui-jcef.xml, so it only exists when JCEF is available. */
class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            val label = JBLabel("The chat panel needs JCEF, which is not available in this IDE.", SwingConstants.CENTER)
            toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(label, "", false))
            return
        }
        project.service<ChatTabs>().install(toolWindow) // tabs: one ChatPanel per conversation (Phase 10b)
    }
}
