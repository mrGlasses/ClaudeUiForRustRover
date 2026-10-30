package dev.metaclaude.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Panel rule 3: only http/https links with a host ever reach the system browser. */
class ChatPanelUrlTest {
    @Test
    fun `http and https are allowed`() {
        assertTrue(ChatPanel.isSafeExternalUrl("https://www.anthropic.com/news"))
        assertTrue(ChatPanel.isSafeExternalUrl("http://localhost:3000"))
    }

    @Test
    fun `everything else is refused`() {
        listOf(
            "javascript:alert(1)", "file:///C:/Windows/win.ini", "data:text/html,<b>x</b>", "vbscript:x",
            "\\\\server\\share\\x", "https://", "ftp://example.com/x", "", "not a url",
            "https://example.com/" + "a".repeat(3000),
        ).forEach { assertFalse(it, ChatPanel.isSafeExternalUrl(it)) }
    }
}
