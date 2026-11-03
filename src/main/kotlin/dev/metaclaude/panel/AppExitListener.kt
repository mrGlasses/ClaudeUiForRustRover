package dev.metaclaude.panel

import com.intellij.ide.AppLifecycleListener

/**
 * Phase 16a: when the IDE closes, every chat process gets a short chance to end on its own (stdin closed), then it and
 * whatever it started are stopped, so no `claude` (or its MCP servers) outlives the IDE.
 */
class AppExitListener : AppLifecycleListener {
    override fun appWillBeClosed(isRestart: Boolean) = ChatSession.stopAllForExit()
}
