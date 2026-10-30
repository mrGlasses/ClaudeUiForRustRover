package dev.metaclaude.compat

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/**
 * How IDE features (diff, selection, diagnostics) reach the CLI.
 *
 *  BUILTIN  our own bridge (lock file + WS server). Works with or without the official plugin.
 *  OFFICIAL no bridge of ours; the official plugin provides the IDE features.
 *  AUTO     OFFICIAL if the official plugin is installed + enabled, otherwise BUILTIN.
 * OFFICIAL and AUTO fall back to BUILTIN when the official plugin is missing or disabled.
 */
enum class IntegrationMode(private val label: String) {
    BUILTIN("Built-in (this plugin's bridge)"),
    OFFICIAL("Official Claude Code plugin"),
    AUTO("Auto (official if enabled, else built-in)");

    override fun toString() = label // shown in the settings combo box
}

enum class OfficialState { NOT_INSTALLED, DISABLED, ENABLED }

object OfficialPluginDetector {
    /** Plugin ID seen in bug reports for the official "Claude Code [Beta]" plugin. */
    const val PLUGIN_ID = "com.anthropic.code.plugin"

    /** Public APIs only (getPlugin / findEnabledPlugin are @ApiStatus.Internal since 2026.2). */
    fun state(): OfficialState {
        val id = PluginId.getId(PLUGIN_ID)
        if (!PluginManagerCore.isPluginInstalled(id)) return OfficialState.NOT_INSTALLED
        return if (PluginManagerCore.isLoaded(id) && !PluginManagerCore.isDisabled(id)) OfficialState.ENABLED
        else OfficialState.DISABLED
    }

    /** Resolve a user-chosen mode to what we will actually do. */
    fun effective(mode: IntegrationMode): IntegrationMode = when (mode) {
        IntegrationMode.BUILTIN -> IntegrationMode.BUILTIN
        IntegrationMode.OFFICIAL, IntegrationMode.AUTO ->
            if (state() == OfficialState.ENABLED) IntegrationMode.OFFICIAL else IntegrationMode.BUILTIN
    }
}
