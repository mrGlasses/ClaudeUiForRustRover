package dev.metaclaude

import java.util.Properties

object PluginInfo {
    /** Must match <id> in plugin.xml. */
    const val ID = "io.github.mrglasses.clawedui"
    const val NAME = "Clawed UI"

    /** Label shown in the CLI's /ide list. */
    const val IDE_NAME = "RustRover (Clawed UI)"

    /**
     * Written into claude-ui.properties by Gradle (processResources) from `version` in build.gradle.kts.
     * No IntelliJ API needed: the plugin-descriptor lookups are @ApiStatus.Internal since 2026.2 (plugin verifier).
     */
    val version: String by lazy {
        runCatching {
            PluginInfo::class.java.getResourceAsStream("/claude-ui.properties")!!.use { Properties().apply { load(it) } }
                .getProperty("version")
        }.getOrNull() ?: "unknown"
    }
}
