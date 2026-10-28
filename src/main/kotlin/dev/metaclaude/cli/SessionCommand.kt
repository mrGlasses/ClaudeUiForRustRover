package dev.metaclaude.cli

/** The line typed into the terminal to start `claude`, in the syntax of the user's shell. */
object SessionCommand {
    /**
     * [onPath]: claude was found on PATH, so the bare name works in any shell.
     * Otherwise the full path is quoted; PowerShell (RustRover's default shell on Windows) also needs the call operator `&`.
     */
    fun build(exe: String, onPath: Boolean, shellPath: String?, windows: Boolean): String {
        if (onPath) return "claude"
        val shell = (shellPath ?: "").lowercase()
        val powershell = shell.contains("powershell") || shell.contains("pwsh") || (windows && shell.isBlank())
        return if (powershell) "& \"$exe\"" else "\"$exe\""
    }
}
