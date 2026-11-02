package dev.metaclaude.panel

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.JBProtocolCommand
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import dev.metaclaude.protocol.BridgeLog
import dev.metaclaude.protocol.UriCommands

/**
 * Phase 14c: VS Code's URI handler as a JetBrains protocol command (`jetbrains://rustrover/clawed-ui/...`):
 *  - `open?prompt=...&session=...`: opens the chat (the session in a new tab) with the prompt in the message box. Nothing
 *    is sent: a link from a web page never runs a prompt by itself.
 *  - `install-plugin?plugin=...&marketplace=...`: opens Customize > Plugins filtered to that plugin; installing still
 *    takes your click and the native confirmation.
 * Links reach a running IDE through JetBrains Toolbox, or `rustrover64.exe "jetbrains://..."` on the command line.
 */
class ClawedProtocolCommand : JBProtocolCommand("clawed-ui") {
    override suspend fun execute(target: String?, parameters: Map<String, String>, fragment: String?): String? {
        val request = UriCommands.parse(target, parameters) ?: return "Unknown Clawed UI link: ${target?.take(40)}"
        val project = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
            ?: ProjectManager.getInstance().openProjects.firstOrNull()
            ?: return "Open a project first."
        BridgeLog.log("protocol link: ${request.kind}", project.name)
        ApplicationManager.getApplication().invokeLater { run(project, request) }
        return null
    }

    private fun run(project: Project, r: UriCommands.Request) {
        val tabs = project.service<ChatTabs>()
        val window = tabs.toolWindow() ?: return
        window.activate({
            val panel = (if (r.session != null) tabs.newTab(r.session) else tabs.selected() ?: tabs.newTab(null)) ?: return@activate
            when (r.kind) {
                "open" -> r.prompt?.let { p -> panel.postWhenReady(JsonObject().apply { addProperty("type", "insertText"); addProperty("text", p) }) }
                "install-plugin" -> panel.postWhenReady(JsonObject().apply {
                    addProperty("type", "openCustomize")
                    addProperty("tab", "plugins")
                    addProperty("filter", r.plugin)
                })
            }
        }, true)
    }
}
