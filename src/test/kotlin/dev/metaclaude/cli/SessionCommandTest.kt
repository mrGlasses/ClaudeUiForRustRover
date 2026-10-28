package dev.metaclaude.cli

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCommandTest {
    private val exe = "C:\\Program Files\\Claude\\claude.exe"

    @Test
    fun `on PATH the bare name works everywhere`() {
        assertEquals("claude", SessionCommand.build(exe, onPath = true, shellPath = "cmd.exe", windows = true))
    }

    @Test
    fun `PowerShell needs the call operator`() {
        assertEquals("& \"$exe\"", SessionCommand.build(exe, false, "powershell.exe", windows = true))
        assertEquals("& \"$exe\"", SessionCommand.build(exe, false, "C:\\Program Files\\PowerShell\\7\\pwsh.exe", windows = true))
        assertEquals("& \"$exe\"", SessionCommand.build(exe, false, null, windows = true)) // RustRover's Windows default
    }

    @Test
    fun `cmd and bash take the quoted path`() {
        assertEquals("\"$exe\"", SessionCommand.build(exe, false, "cmd.exe", windows = true))
        assertEquals("\"$exe\"", SessionCommand.build(exe, false, "C:\\Program Files\\Git\\bin\\bash.exe", windows = true))
        assertEquals("\"/home/n/.local/bin/claude\"", SessionCommand.build("/home/n/.local/bin/claude", false, "/bin/zsh", windows = false))
    }
}
