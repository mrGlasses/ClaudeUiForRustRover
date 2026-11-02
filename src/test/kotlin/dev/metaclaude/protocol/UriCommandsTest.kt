package dev.metaclaude.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UriCommandsTest {
    @Test
    fun `open links`() {
        val r = UriCommands.parse("open", mapOf("prompt" to "Explain\u0007 main.rs\nplease", "session" to "0b4c1d2e-1111-4222-8333-944445555666"))!!
        assertEquals("Explain main.rs\nplease", r.prompt) // control characters dropped, newlines kept
        assertEquals("0b4c1d2e-1111-4222-8333-944445555666", r.session)
        assertNull(UriCommands.parse("open", mapOf("session" to "../../x"))!!.session)
        assertEquals(UriCommands.PROMPT_MAX, UriCommands.parse("/open/", mapOf("prompt" to "x".repeat(20_000)))!!.prompt!!.length)
    }

    @Test
    fun `install-plugin links`() {
        assertEquals("frontend-design@claude-plugins-official", UriCommands.parse("install-plugin", mapOf("plugin" to "frontend-design", "marketplace" to "claude-plugins-official"))!!.plugin)
        assertEquals("x", UriCommands.parse("install-plugin", mapOf("plugin" to "x", "marketplace" to "bad name"))!!.plugin)
        assertNull(UriCommands.parse("install-plugin", mapOf("plugin" to "a b")))
        assertNull(UriCommands.parse("delete-everything", emptyMap()))
    }

    @Test
    fun `process options`() {
        val (env, bad) = ProcessOptions.parseEnv("# provider\nCLAUDE_CODE_USE_BEDROCK=1\n AWS_REGION = us-east-1 \nTOKEN=\"a=b\"\n9BAD=x\nno equals\n")
        assertEquals(mapOf("CLAUDE_CODE_USE_BEDROCK" to "1", "AWS_REGION" to "us-east-1", "TOKEN" to "a=b"), env)
        assertEquals(2, bad.size)
        assertEquals(listOf("wrap.exe", "claude.exe", "-p"), ProcessOptions.command(" wrap.exe ", "claude.exe", listOf("-p")))
        assertEquals(listOf("claude.exe", "-p"), ProcessOptions.command("", "claude.exe", listOf("-p")))
        assertEquals("2: AWS_REGION, TOKEN", ProcessOptions.describe(mapOf("TOKEN" to "secret", "AWS_REGION" to "x")))
        assertTrue(!ProcessOptions.describe(mapOf("TOKEN" to "secret")).contains("secret"))
    }
}
