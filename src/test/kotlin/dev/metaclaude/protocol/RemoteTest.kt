package dev.metaclaude.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 15b: the @browser check used before a session starts (same pattern as ChatPanel). */
class RemoteTest {
    private val browser = Regex("(^|\\s)@browser\\b")

    @Test
    fun `browser mention`() {
        assertTrue(browser.containsMatchIn("@browser open docs.rs"))
        assertTrue(browser.containsMatchIn("check this @browser"))
        assertFalse(browser.containsMatchIn("email me@browser.com"))
        assertFalse(browser.containsMatchIn("@browsers"))
    }
}
