package dev.metaclaude.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDiffsTest {
    private val diffs = PendingDiffs<String>()

    @Test
    fun `a call is answered exactly once`() {
        val f = diffs.open("a", "REPLACED")
        assertTrue(diffs.isPending("a"))
        assertTrue(diffs.resolve("a", "FIRST"))
        assertFalse("second answer must be ignored", diffs.resolve("a", "SECOND"))
        assertEquals("FIRST", f.get())
        assertFalse(diffs.isPending("a"))
    }

    @Test
    fun `re-opening a tab answers the old call first`() {
        val old = diffs.open("a", "REPLACED")
        val new = diffs.open("a", "REPLACED")
        assertEquals("REPLACED", old.get())
        assertFalse(new.isDone)
        diffs.resolve("a", "OK")
        assertEquals("OK", new.get())
    }

    @Test
    fun `resolveAll answers every open call`() {
        val a = diffs.open("a", "R")
        val b = diffs.open("b", "R")
        assertEquals(2, diffs.resolveAll("CLOSED"))
        assertEquals("CLOSED", a.get())
        assertEquals("CLOSED", b.get())
        assertEquals(0, diffs.resolveAll("CLOSED"))
    }

    @Test
    fun `fail completes exceptionally`() {
        val f = diffs.open("a", "R")
        assertTrue(diffs.fail("a", IllegalStateException("x")))
        assertTrue(f.isCompletedExceptionally)
    }

    @Test
    fun `resolving an unknown tab is a no-op`() {
        assertFalse(diffs.resolve("nope", "X"))
    }
}

class DiffReplyTest {
    @Test
    fun `unchanged proposal is returned byte for byte, even with CRLF`() {
        val proposal = "fn main() {\r\n    println!(\"hi\");\r\n}\r\n"
        val docText = DiffReply.toDocument(proposal)
        assertFalse(docText.contains('\r'))
        assertEquals(proposal, DiffReply.finalText(proposal, docText))
    }

    @Test
    fun `edited text keeps the proposal's CRLF style`() {
        val proposal = "a\r\nb\r\n"
        assertEquals("a\r\nB\r\n", DiffReply.finalText(proposal, "a\nB\n"))
    }

    @Test
    fun `edited text with LF proposal stays LF`() {
        assertEquals("a\nB\n", DiffReply.finalText("a\nb\n", "a\nB\n"))
    }

    @Test
    fun `saved reply has FILE_SAVED then the final text`() {
        val content = DiffReply.saved("x\n", "y\n").getAsJsonArray("content")
        assertEquals(2, content.size())
        assertEquals("FILE_SAVED", content[0].asJsonObject.get("text").asString)
        assertEquals("y\n", content[1].asJsonObject.get("text").asString)
    }

    @Test
    fun `rejected and tab closed replies`() {
        assertEquals("DIFF_REJECTED", DiffReply.rejected().getAsJsonArray("content")[0].asJsonObject.get("text").asString)
        assertEquals("TAB_CLOSED", DiffReply.tabClosed().getAsJsonArray("content")[0].asJsonObject.get("text").asString)
    }
}
