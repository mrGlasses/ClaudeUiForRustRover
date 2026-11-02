package dev.metaclaude.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes of claude 2.1.316's get_sandbox_dialog and get_chrome_dialog. */
class SandboxChromeTest {
    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    private val sandbox = obj("""{"supported":true,"locked":false,"overrides_locked":false,"enabled":true,"enabled_in_settings":true,
        "mode":"regular","auto_allow_available":false,"no_sandbox_allowed":true,"unsandboxed_fallback":false,
        "dependencies":{"errors":[],"warnings":["socat missing: unix socket filtering off"]},"excluded_commands":["docker:*"],
        "restrictions":{"fs_deny_read":["~/.ssh"],"fs_allow_read":[],"fs_allow_write":[],"fs_deny_write":[],"network_allowed_domains":["crates.io"],
          "network_denied_domains":[],"network_managed":false,"unix_sockets":[],"allow_all_unix_sockets":false,"ignored_glob_patterns":[]}}""")

    @Test
    fun `sandbox for the panel`() {
        val s = SandboxChrome.sandbox(sandbox)
        assertEquals("regular", s.get("mode").asString)
        assertEquals("docker:*", s.getAsJsonArray("excluded")[0].asString)
        assertEquals(2, s.getAsJsonArray("restrictions").size()) // only the non-empty lists
        val off = SandboxChrome.sandbox(obj("""{"supported":false,"unsupported_reason":"platform"}"""))
        assertFalse(off.get("supported").asBoolean)
        assertEquals("Sandboxing isn't available on this platform.", off.get("unsupportedReason").asString)
    }

    @Test
    fun `sandbox edits only for what the dialog offers`() {
        assertEquals("disabled", SandboxChrome.sandboxEdit(sandbox, "mode", "disabled", null)!!.get("mode").asString)
        assertNull(SandboxChrome.sandboxEdit(sandbox, "mode", "auto-allow", null)) // not offered here
        assertNull(SandboxChrome.sandboxEdit(sandbox, "mode", "yolo", null))
        assertTrue(SandboxChrome.sandboxEdit(sandbox, "fallback", null, true)!!.get("allowUnsandboxedCommands").asBoolean)
        assertEquals("cargo:*", SandboxChrome.sandboxEdit(sandbox, "exclude", " cargo:* ", null)!!.get("excludeCommand").asString)
        assertNull(SandboxChrome.sandboxEdit(sandbox, "exclude", "a\nb", null))
        assertNull(SandboxChrome.sandboxEdit(obj("""{"supported":true,"locked":true,"mode":"regular"}"""), "mode", "disabled", null))
        assertNull(SandboxChrome.sandboxEdit(obj("""{"supported":false}"""), "exclude", "x", null))
    }

    private val chrome = obj("""{"allowed":true,"subscriber":true,"wsl":false,"installed":true,"connected":false,"enabled_by_default":false,
        "urls":{"install":"https://claude.ai/chrome","reconnect":"https://clau.de/chrome/reconnect","permissions":"javascript:alert(1)"}}""")

    @Test
    fun `chrome state and links`() {
        val c = SandboxChrome.chrome(chrome)
        assertTrue(c.get("installed").asBoolean)
        assertTrue(c.has("has_reconnect"))
        assertFalse(c.has("has_permissions")) // not an http(s) URL
        assertEquals("https://clau.de/chrome/reconnect", SandboxChrome.chromeUrl(chrome, "reconnect"))
        assertNull(SandboxChrome.chromeUrl(chrome, "permissions"))
        assertNull(SandboxChrome.chromeUrl(chrome, "elsewhere"))
        assertTrue(SandboxChrome.chromeEdit(true).get("enabledByDefault").asBoolean)
    }
}
