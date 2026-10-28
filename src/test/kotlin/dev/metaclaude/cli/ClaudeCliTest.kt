package dev.metaclaude.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeCliTest {
    private fun resolveWin(configured: String?, path: String, vararg existing: String) =
        ClaudeCli.resolve(configured, path, "C:\\Users\\Nick", windows = true) { it in existing }

    @Test
    fun `configured path wins and must exist`() {
        assertEquals("D:\\tools\\claude.exe", resolveWin("D:\\tools\\claude.exe", "C:\\bin", "D:\\tools\\claude.exe", "C:\\bin\\claude.exe"))
        assertNull("a wrong configured path is reported, not silently replaced", resolveWin("D:\\nope.exe", "C:\\bin", "C:\\bin\\claude.exe"))
    }

    @Test
    fun `first PATH folder wins, exe before cmd`() {
        assertEquals(
            "C:\\a\\claude.exe",
            resolveWin(null, "C:\\a;C:\\b", "C:\\a\\claude.exe", "C:\\a\\claude.cmd", "C:\\b\\claude.exe"),
        )
        assertEquals("C:\\npm\\claude.cmd", resolveWin(null, "C:\\empty;C:\\npm\\", "C:\\npm\\claude.cmd"))
    }

    @Test
    fun `quoted PATH entries and the native installer folder`() {
        assertEquals("C:\\q d\\claude.exe", resolveWin(null, "\"C:\\q d\"", "C:\\q d\\claude.exe"))
        assertEquals("C:\\Users\\Nick\\.local\\bin\\claude.exe", resolveWin(null, "C:\\x", "C:\\Users\\Nick\\.local\\bin\\claude.exe"))
    }

    @Test
    fun `not found anywhere`() {
        assertNull(resolveWin(null, "C:\\x;C:\\y"))
        assertNull(resolveWin("  ", ""))
    }

    @Test
    fun `unix PATH`() {
        assertEquals(
            "/usr/local/bin/claude",
            ClaudeCli.resolve(null, "/usr/bin:/usr/local/bin", "/home/n", windows = false) { it == "/usr/local/bin/claude" },
        )
    }
}
