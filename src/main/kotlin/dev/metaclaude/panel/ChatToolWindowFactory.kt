package dev.metaclaude.panel

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import dev.metaclaude.protocol.BridgeLog
import javax.swing.SwingConstants

/** "Claude Chat" tool window. Registered from claude-ui-jcef.xml, so it only exists when JCEF is available. */
class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        if (!JBCefApp.isSupported()) {
            val label = JBLabel("The chat panel needs JCEF, which is not available in this IDE.", SwingConstants.CENTER)
            toolWindow.contentManager.addContent(factory.createContent(label, "", false))
            return
        }
        val component = try {
            ChatPanel(project, toolWindow.disposable).component // disposed with the tool window
        } catch (e: Throwable) {
            BridgeLog.log("panel failed to open: ${e::class.java.name}: ${e.message}", project.name)
            JBLabel("The chat panel failed to open: ${e.message}", SwingConstants.CENTER)
        }
        toolWindow.contentManager.addContent(factory.createContent(component, "Preview", false))
    }
}
