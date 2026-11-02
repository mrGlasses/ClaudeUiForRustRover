package dev.metaclaude.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustHelpTest {
    @Test
    fun `fix prompt mentions the file and lines`() {
        val one = RustHelp.fixPrompt("src/main.rs", listOf(RustHelp.Problem("error", "mismatched types\nexpected `u32`", 12)))
        assertTrue(one.startsWith("Fix this problem in @src/main.rs#12:"))
        assertTrue(one.contains("- error (line 12): mismatched types"))
        assertFalse(one.contains("expected `u32`")) // first line only
        val two = RustHelp.fixPrompt("src/lib.rs", listOf(RustHelp.Problem("warning", "unused variable `x`", 4), RustHelp.Problem("error", "cannot borrow", 6)))
        assertTrue(two.startsWith("Fix these problems in @src/lib.rs#4-6:"))
    }

    @Test
    fun `console output is fenced and bounded`() {
        val p = RustHelp.consolePrompt("Test my_crate", "error[E0308]: mismatched types\r\n\u001b --> src/main.rs:3:5\n")
        assertTrue(p.contains("\"Test my_crate\""))
        assertTrue(p.contains("```text\nerror[E0308]: mismatched types\n --> src/main.rs:3:5\n```"))
        val long = RustHelp.consolePrompt(null, "x".repeat(20_000))
        assertTrue(long.contains("output of a run"))
        assertTrue(long.contains("…\n" + "x".repeat(RustHelp.OUTPUT_MAX)))
        assertTrue(RustHelp.consolePrompt(null, "has ``` inside").contains("~~~text"))
    }

    @Test
    fun `toolchain folder goes first on PATH`() {
        assertEquals("C:\\t\\bin;C:\\Windows", RustHelp.pathWith(" C:\\t\\bin ", "C:\\Windows", ";"))
        assertEquals("/t/bin", RustHelp.pathWith("/t/bin", null, ":"))
        assertNull(RustHelp.pathWith("  ", "/usr/bin", ":"))
        assertEquals(3, RustHelp.QUICK.size)
    }
}
