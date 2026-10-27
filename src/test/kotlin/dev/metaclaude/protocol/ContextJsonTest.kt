package dev.metaclaude.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextJsonTest {
    @Test
    fun `Windows paths use backslashes for the CLI`() {
        assertEquals("C:\\Users\\Nick\\a b\\main.rs", CliPaths.forCli("C:/Users/Nick/a b/main.rs", windows = true))
        assertEquals("/home/nick/main.rs", CliPaths.forCli("/home/nick/main.rs", windows = false))
    }

    @Test
    fun `file URLs look like VS Code's`() {
        assertEquals("file:///C:/Users/Nick/a%20b/main.rs", CliPaths.fileUrl("C:\\Users\\Nick\\a b\\main.rs"))
        assertEquals("file:///home/nick/main.rs", CliPaths.fileUrl("/home/nick/main.rs"))
    }

    @Test
    fun `selection payload has text, paths and 0-based range`() {
        val p = ContextJson.selection("let x = 1;", "C:\\p\\main.rs", 4, 0, 4, 10)
        assertEquals("let x = 1;", p.get("text").asString)
        assertEquals("C:\\p\\main.rs", p.get("filePath").asString)
        assertEquals("file:///C:/p/main.rs", p.get("fileUrl").asString)
        val sel = p.getAsJsonObject("selection")
        assertEquals(4, sel.getAsJsonObject("start").get("line").asInt)
        assertEquals(10, sel.getAsJsonObject("end").get("character").asInt)
        assertFalse(sel.get("isEmpty").asBoolean)
    }

    @Test
    fun `cursor only is an empty selection`() {
        val p = ContextJson.selection("", "C:\\p\\main.rs", 7, 3, 7, 3)
        assertTrue(p.getAsJsonObject("selection").get("isEmpty").asBoolean)
    }

    @Test
    fun `at mention payload`() {
        val p = ContextJson.atMention("C:\\p\\main.rs", 4, 9)
        assertEquals("C:\\p\\main.rs", p.get("filePath").asString)
        assertEquals(4, p.get("lineStart").asInt)
        assertEquals(9, p.get("lineEnd").asInt)
    }
}
