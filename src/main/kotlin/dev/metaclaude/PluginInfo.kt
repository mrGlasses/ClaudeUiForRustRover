package dev.metaclaude

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

object PluginInfo {
    /** Must match <id> in plugin.xml. */
    const val ID = "io.github.mrglasses.claudeui"
    const val NAME = "Claude UI for RustRover"

    /** Label shown in the CLI's /ide list. */
    const val IDE_NAME = "RustRover (Claude UI)"

    /** Set by Gradle from `version` in build.gradle.kts. */
    val version: String
        get() = PluginManagerCore.getPlugin(PluginId.getId(ID))?.version ?: "unknown"
}
