package dev.metaclaude

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Runs once per opened project: starts the IDE bridge so `/ide` finds us from any terminal. */
class BridgeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<MetaClaudeService>().startBridgeIfEnabled()
    }
}
