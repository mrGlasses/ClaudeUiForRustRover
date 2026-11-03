package dev.metaclaude.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import dev.metaclaude.compat.IntegrationMode

/**
 * Application-wide settings, saved in claude-ui.xml in the IDE's config folder. The file keeps its pre-rename name
 * (Clawed UI, 2026-10-30) so existing settings carry over.
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
        /**
         * Chat panel to-do list. Claude Code leaves its task tools out by default on recent models (Opus 4.8+, Sonnet 5+);
         * on, the panel starts claude with CLAUDE_CODE_ENABLE_TODO_TOOLS=1 so Claude keeps a visible list.
         */
        var todoTools by property(false)
        /**
         * Chat panel thinking blocks. Without Claude Code's `showThinkingSummaries` the CLI sends empty thinking blocks
         * (v0.16.1 test); on, the panel starts claude with `--settings {"showThinkingSummaries":true}`.
         */
        var thinkingSummaries by property(true)
        /** VS Code `showMessageTimestamps`: times on messages and date lines in the chat panel. */
        var showMessageTimestamps by property(true)
        /** VS Code `initialPermissionMode`: mode of each new chat-panel conversation (default, acceptEdits, plan, auto). */
        var initialPermissionMode by string("default")
        /** VS Code `allowDangerouslySkipPermissions`: offer "Bypass permissions" in the panel's mode menu. */
        var allowDangerouslySkipPermissions by property(false)
        /** VS Code `autosave`: save open files before Claude reads or writes them (before each message and each edit). */
        var autosave by property(true)
        /** VS Code `continueAfterReload`: reopen the last conversation when the panel opens, if it was active < 10 min ago. */
        var continueAfterReload by property(true)
        /** VS Code `archiveInactiveSessions`: days without activity before a conversation counts as archived (0 = never). */
        var archiveInactiveSessions by property(0)
        /** VS Code `preferredLocation`: side of the IDE for the Claude Chat panel (right, left, bottom). */
        var preferredLocation by string("right")
        /** Phase 14b, VS Code `useCtrlEnterToSend`: Enter adds a line, Ctrl+Enter sends. */
        var useCtrlEnterToSend by property(false)
        /** VS Code `scrollToBottomOnSend`: jump to the end of the conversation when you send. */
        var scrollToBottomOnSend by property(true)
        /** VS Code `focusView`: new conversations start in Focus view (tool calls and thinking hidden). */
        var focusView by property(false)
        /** VS Code `spinnerVerbs`: words shown while Claude works, comma-separated ("" = just "Working"). */
        var spinnerVerbs by string("")
        /** VS Code `hideOnboarding`: no getting-started list in an empty conversation. */
        var hideOnboarding by property(false)
        /** Phase 14c, VS Code `environmentVariables`: KEY=value lines for every claude the plugin starts. */
        var environmentVariables by string("")
        /** VS Code `claudeProcessWrapper`: a program that starts claude (gets claude's path and arguments). */
        var claudeProcessWrapper by string("")
        /** VS Code `disableLoginPrompt`: no sign-in card (third-party providers sign in their own way). */
        var disableLoginPrompt by property(false)
        /** Phase 15a, VS Code's usePythonEnvironment for Rust: a toolchain folder (…/bin with cargo, rustc) put first on PATH. */
        var rustToolchainDir by string("")
        /** Phase 15b, VS Code's "Enable Remote Control for all sessions": every new chat conversation starts it. */
        var remoteControlAtStartup by property(false)
    }

    /** Phase 14c: the extra environment and the command line every chat-panel `claude` uses. */
    fun processEnv(): Map<String, String> = dev.metaclaude.protocol.ProcessOptions.parseEnv(state.environmentVariables).first

    /** Adds [processEnv] and the Rust toolchain folder (first on PATH) to a process environment. */
    fun applyEnv(env: MutableMap<String, String>) {
        env.putAll(processEnv())
        val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        dev.metaclaude.protocol.RustHelp.pathWith(state.rustToolchainDir, env[pathKey], java.io.File.pathSeparator)?.let { env[pathKey] = it }
    }
    fun command(exe: String, args: List<String>): List<String> =
        dev.metaclaude.protocol.ProcessOptions.command(state.claudeProcessWrapper, exe, args)

    var integrationMode: IntegrationMode
        get() = IntegrationMode.entries.firstOrNull { it.name == state.integrationModeName } ?: IntegrationMode.BUILTIN
        set(value) { state.integrationModeName = value.name }

    companion object {
        val instance: ClaudeUiSettings get() = service()
    }
}
