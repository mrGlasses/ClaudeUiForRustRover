package dev.metaclaude.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelContextTest {
    @Test
    fun `context blocks come before the prompt`() {
        val c = PanelContext.content("explain", "C:\\p\\main.rs", PanelContext.Selection("C:\\p\\main.rs", 5, 7, "fn a() {}"))
        assertEquals(3, c.size())
        assertTrue(c[0].asJsonObject.get("text").asString.startsWith("<ide_opened_file>The user opened the file C:\\p\\main.rs"))
        val sel = c[1].asJsonObject.get("text").asString
        assertTrue(sel.startsWith("<ide_selection>The user selected the lines 5 to 7 from C:\\p\\main.rs:\nfn a() {}"))
        assertEquals("explain", c[2].asJsonObject.get("text").asString)
        assertEquals("text", c[2].asJsonObject.get("type").asString)
    }

    @Test
    fun `withheld text and single lines`() {
        val s = PanelContext.selection(PanelContext.Selection("a.rs", 3, 3, null))
        assertTrue(s.contains("the line 3 from a.rs (text not shared)"))
        assertEquals(1, PanelContext.content("hi", null, null).size())
    }

    @Test
    fun `fuzzy search prefers file names and word starts`() {
        val paths = listOf("src/main/kotlin/dev/metaclaude/panel/ChatPanel.kt", "src/main/kotlin/dev/metaclaude/panel/ChatSession.kt",
            "webview/src/main.ts", "PLAN.md", "src/", "webview/", "src/test/kotlin/dev/metaclaude/protocol/PermissionsTest.kt")
        assertEquals("src/main/kotlin/dev/metaclaude/panel/ChatPanel.kt", FuzzyPaths.top("chatpan", paths).first())
        assertEquals("PLAN.md", FuzzyPaths.top("plan", paths).first())
        assertEquals("webview/src/main.ts", FuzzyPaths.top("main.ts", paths).first())
        assertTrue(FuzzyPaths.top("xyz", paths).isEmpty())
        assertNull(FuzzyPaths.score("zz", "abc"))
        assertEquals(listOf("src/", "webview/", "PLAN.md"), FuzzyPaths.top("", paths))
    }

    @Test
    fun `terminal mentions and output blocks`() {
        assertEquals(listOf("Local-(2)", "cargo"), PanelContext.terminalMentions("see @terminal:Local-(2) and @terminal:cargo, ok @terminal:cargo"))
        assertEquals("Local-(2)", PanelContext.terminalToken(" Local  (2) "))
        val b = PanelContext.terminal("cargo", "x".repeat(PanelContext.TERMINAL_MAX + 10))
        assertTrue(b.startsWith("<terminal_output name=\"cargo\">"))
        assertTrue(b.contains("[earlier output cut]"))
        assertTrue(b.length < PanelContext.TERMINAL_MAX + 200)
    }

    @Test
    fun `images are checked and go before the prompt`() {
        assertNull(PanelContext.image("image/svg+xml", "AAAA"))
        assertNull(PanelContext.image("image/png", "not base64!"))
        assertNull(PanelContext.image("image/png", "A".repeat(PanelContext.IMAGE_MAX_BASE64 + 4)))
        val img = PanelContext.image("image/png", "iVBORw0KGgo=")!!
        val c = PanelContext.content("what is this?", null, null, listOf("t" to "out"), listOf(img))
        assertEquals(listOf("text", "image", "text"), c.map { it.asJsonObject.get("type").asString })
        assertEquals("image/png", c[1].asJsonObject.getAsJsonObject("source").get("media_type").asString)
        assertEquals("what is this?", c[2].asJsonObject.get("text").asString)
    }
}
