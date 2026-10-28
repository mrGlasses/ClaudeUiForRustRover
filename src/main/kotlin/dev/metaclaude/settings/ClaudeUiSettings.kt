package dev.metaclaude.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import dev.metaclaude.compat.IntegrationMode

/**
 * Application-wide settings, saved in claude-ui.xml in the IDE's config folder.
 * Only settings that already have an effect live here; the VS Code panel settings come with the phases that use them.
 */
@Service(Service.Level.APP)
@State(name = "ClaudeUiSettings", storages = [Storage("claude-ui.xml")])
class ClaudeUiSettings : SimplePersistentStateComponent<ClaudeUiSettings.Options>(Options()) {
    class Options : BaseState() {
        var integrationModeName by string(IntegrationMode.BUILTIN.name)
        /** Empty: search PATH and ~/.local/bin. */
        var claudePath by string()
        /** VS Code `attachSelection`: send the selected text. */
        var attachSelection by property(true)
        /** VS Code `attachOpenFile`: send which file is open even without a selection. */
        var attachOpenFile by property(true)
        /** VS Code `respectGitIgnore`: never send text from git-ignored files. */
        var respectGitIgnore by property(true)
    }

    var integrationMode: IntegrationMode
        get() = IntegrationMode.entries.firstOrNull { it.name == state.integrationModeName } ?: IntegrationMode.BUILTIN
        set(value) { state.integrationModeName = value.name }

    companion object {
        val instance: ClaudeUiSettings get() = service()
    }
}
