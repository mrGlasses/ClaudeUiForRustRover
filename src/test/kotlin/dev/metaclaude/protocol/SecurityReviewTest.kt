package dev.metaclaude.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 16b: the local-only checks and what the log may keep. */
class SecurityReviewTest {
    @Test fun localProgramsOnly() {
        assertTrue(LocalOnly.requestAllowed("127.0.0.1:5000", null, 5000))
        assertTrue(LocalOnly.requestAllowed("localhost:5000", "", 5000))
        assertTrue(LocalOnly.requestAllowed("[::1]:5000", null, 5000))
        assertTrue(LocalOnly.requestAllowed("127.0.0.1", null, 5000))
        assertTrue(LocalOnly.requestAllowed(null, null, 5000))
        assertFalse(LocalOnly.requestAllowed("127.0.0.1:5000", "https://evil.example", 5000)) // a web page
        assertFalse(LocalOnly.requestAllowed("127.0.0.1:5000", "null", 5000)) // sandboxed iframe
        assertFalse(LocalOnly.requestAllowed("evil.example:5000", null, 5000)) // DNS rebinding
        assertFalse(LocalOnly.requestAllowed("127.0.0.1.evil.example:5000", null, 5000))
        assertFalse(LocalOnly.requestAllowed("127.0.0.1:5001", null, 5000))
    }

    @Test fun ownerMatching() {
        assertTrue(LocalOnly.isUser("DESKTOP-1\\Nick", "Nick"))
        assertTrue(LocalOnly.isUser("nick", "Nick"))
        assertFalse(LocalOnly.isUser("BUILTIN\\Administrators", "Nick"))
    }

    @Test fun replyLogKeepsNoContent() {
        val reply = """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"fn secret() {}"}]}}"""
        val s = McpDispatcher.replySummary(reply)
        assertEquals("result (${reply.length} chars)", s)
        assertEquals("error -32601 (60 chars)", McpDispatcher.replySummary("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"x"}}""").replace(Regex("\\(\\d+ chars\\)"), "(60 chars)"))
    }

    @Test fun logRedactsTokensAndKeepsOneLine() {
        val token = AuthToken.generate()
        val line = BridgeLog.clean("oops $token\nsecond")
        assertFalse(line.contains(token))
        assertFalse(line.contains('\n'))
        assertTrue(line.contains("[redacted]"))
        // session ids (UUIDs) and ports stay readable
        assertEquals("session 0b6f3c2e-1d4a-4f7e-9c1b-2a3d4e5f6a7b port 49232", BridgeLog.clean("session 0b6f3c2e-1d4a-4f7e-9c1b-2a3d4e5f6a7b port 49232"))
        assertTrue(BridgeLog.clean("x".repeat(5000)).length <= 1001)
    }
}
