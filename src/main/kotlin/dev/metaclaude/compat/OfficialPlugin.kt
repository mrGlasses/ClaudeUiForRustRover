package dev.metaclaude.compat

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId

/**
 * How IDE features (diff, selection, diagnostics) reach the CLI.
 *
 *  BUILTIN  our own bridge (lock file + WS server). Works with or without the official plugin.
 *  OFFICIAL no bridge of ours; CLI is launched plain and uses whatever the official plugin advertises.
 *  AUTO     OFFICIAL if the official plugin is installed + enabled AND its lock file shows up in time;
 *           otherwise BUILTIN. A failed health check offers a one-click switch (see MetaClaudeService).
 */
enum class IntegrationMode { BUILTIN, OFFICIAL, AUTO }

enum class OfficialState { NOT_INSTALLED, DISABLED, ENABLED }

object OfficialPluginDetector {
    /** Plugin ID seen in bug reports for the official "Claude Code [Beta]" plugin. */
    const val PLUGIN_ID = "com.anthropic.code.plugin"

    fun state(): OfficialState {
        val id = PluginId.getId(PLUGIN_ID)
        if (PluginManagerCore.getPlugin(id) == null) return OfficialState.NOT_INSTALLED
        return if (PluginManager.getInstance().findEnabledPlugin(id) != null) OfficialState.ENABLED
        else OfficialState.DISABLED
    }

    /** Resolve a user-chosen mode to what we will actually do this launch. */
    fun effective(mode: IntegrationMode): IntegrationMode = when (mode) {
        IntegrationMode.BUILTIN -> IntegrationMode.BUILTIN
        IntegrationMode.OFFICIAL, IntegrationMode.AUTO ->
            if (state() == OfficialState.ENABLED) IntegrationMode.OFFICIAL else IntegrationMode.BUILTIN
    }
}
