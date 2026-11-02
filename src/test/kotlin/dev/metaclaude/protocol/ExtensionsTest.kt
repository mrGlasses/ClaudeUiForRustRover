package dev.metaclaude.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes from claude 2.1.316: mcp_status, and `claude plugin list --json --available` / `configure --json` as run in the sandbox. */
class ExtensionsTest {
    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    private val mcp = obj("""{"mcpServers":[
        {"name":"clawed-ui","status":"connected","scope":"dynamic","config":{"type":"http","url":"http://127.0.0.1:1/mcp"},"tools":[{"name":"getDiagnostics"}]},
        {"name":"github","status":"needs-auth","scope":"user","config":{"type":"http","url":"https://api.githubcopilot.com/mcp/"}},
        {"name":"fs","status":"failed","error":"spawn ENOENT","scope":"local","config":{"type":"stdio","command":"npx","args":["-y","@x/fs"]}},
        {"name":"plug","status":"connected","source":"plugin"}]}""")

    @Test
    fun `mcp rows`() {
        val rows = Extensions.mcpServers(mcp).map { it.asJsonObject }
        assertTrue(rows[0].get("own").asBoolean)
        assertFalse(rows[0].get("removable").asBoolean)
        assertEquals("https://api.githubcopilot.com/mcp/", rows[1].get("target").asString)
        assertEquals("npx -y @x/fs", rows[2].get("target").asString)
        assertEquals("spawn ENOENT", rows[2].get("error").asString)
        assertFalse(rows[3].get("removable").asBoolean) // plugin server: managed with its plugin
        assertEquals(listOf("mcp", "remove", "fs", "-s", "local"), Extensions.mcpRemoveArgs(Extensions.mcpRow(mcp, "fs")))
        assertNull(Extensions.mcpRemoveArgs(Extensions.mcpRow(mcp, "clawed-ui")))
    }

    @Test
    fun `mcp add arguments`() {
        assertEquals(
            listOf("mcp", "add", "-s", "local", "-e", "TOKEN=abc", "github", "npx", "--", "-y", "@modelcontextprotocol/server-github"),
            Extensions.mcpAddArgs("github", "stdio", "local", "npx", "-y\n@modelcontextprotocol/server-github\n", "TOKEN=abc", viaCmd = true).getOrThrow(),
        )
        assertEquals(
            listOf("mcp", "add", "-s", "user", "-t", "http", "-H", "Authorization: Bearer x", "docs", "https://mcp.example.com/mcp"),
            Extensions.mcpAddArgs("docs", "http", "user", "https://mcp.example.com/mcp", "ignored", "Authorization: Bearer x", viaCmd = false).getOrThrow(),
        )
        assertTrue(Extensions.mcpAddArgs("bad name", "stdio", "local", "npx", null, null, false).isFailure)
        assertTrue(Extensions.mcpAddArgs("clawed-ui", "stdio", "local", "npx", null, null, false).isFailure)
        assertTrue(Extensions.mcpAddArgs("x", "http", "local", "ftp://x", null, null, false).isFailure)
        assertTrue(Extensions.mcpAddArgs("x", "stdio", "local", "npx", null, "not a pair", false).isFailure)
        // cmd.exe would expand % and break quotes: refused only when the CLI is a .cmd
        assertTrue(Extensions.mcpAddArgs("x", "stdio", "local", "npx", null, "K=%PATH%", viaCmd = true).isFailure)
        assertTrue(Extensions.mcpAddArgs("x", "stdio", "local", "npx", null, "K=%PATH%", viaCmd = false).isSuccess)
        assertTrue(Extensions.viaCmd("C:\\npm\\claude.CMD"))
        assertFalse(Extensions.viaCmd("C:\\Users\\Nick\\.local\\bin\\claude.exe"))
    }

    private val listing = JsonParser.parseString("""{"installed":[{"id":"hello@testmkt","version":"1.0.0","scope":"user","enabled":true,"installPath":"/x","hasUserConfig":true}],
        "available":[{"pluginId":"other@testmkt","name":"other","description":"Other one","marketplaceName":"testmkt","source":"./plugins/hello"},
                     {"pluginId":"bad id; rm","name":"x"}]}""")

    @Test
    fun `plugins and marketplaces`() {
        val p = Extensions.plugins(listing, JsonParser.parseString("""[{"name":"testmkt","source":"directory","path":"/m","installLocation":"/m"}]"""))
        assertEquals("hello@testmkt", p.getAsJsonArray("installed")[0].asJsonObject.get("id").asString)
        assertEquals(1, p.getAsJsonArray("available").size()) // the malformed id is dropped
        assertEquals("directory: /m", p.getAsJsonArray("marketplaces")[0].asJsonObject.get("source").asString)
        // `plugin list --json` without --available is a bare array
        assertEquals(1, Extensions.plugins(JsonParser.parseString("""[{"id":"a@b","scope":"user","enabled":false}]"""), null).getAsJsonArray("installed").size())

        assertEquals(listOf("plugin", "install", "other@testmkt", "-s", "project", "--json"), Extensions.pluginArgs("install", "other@testmkt", "project"))
        assertNull(Extensions.pluginArgs("install", "other@testmkt", "everywhere"))
        assertNull(Extensions.pluginArgs("rm", "other@testmkt", "user"))
        assertNull(Extensions.pluginArgs("enable", "x\" & calc", "user"))
        assertEquals(listOf("plugin", "marketplace", "add", "anthropics/claude-plugins-official"), Extensions.marketplaceArgs("add", " anthropics/claude-plugins-official ", true))
        assertNull(Extensions.marketplaceArgs("add", "--evil", false))
        assertNull(Extensions.marketplaceArgs("add", "C:\\x\"y", true))
        assertEquals(listOf("plugin", "marketplace", "update"), Extensions.marketplaceArgs("update", null, false))
    }

    private val config = obj("""{"pluginId":"hello@testmkt","displayName":"hello","schema":{"greeting":{"type":"string","title":"Greeting","description":"What to say"},
        "token":{"type":"string","title":"Token","sensitive":true}},"inputs":{"greeting":"oi","token":"secret"},"choices":{},"configured":["greeting"],"unconfigured":["token"]}""")

    @Test
    fun `plugin options form and values`() {
        val f = Extensions.configForm(config).getAsJsonArray("fields").map { it.asJsonObject }
        assertEquals("oi", f[0].get("value").asString)
        assertTrue(f[1].get("secret").asBoolean)
        assertEquals("", f[1].get("value").asString) // never sent to the page

        val v = Extensions.configValues(config, obj("""{"greeting":"olá","token":"","unknown":"x","greeting2":"y"}"""))!!
        assertEquals("olá", v.get("greeting").asString)
        assertFalse(v.has("token")) // empty secret: keep the stored one
        assertFalse(v.has("unknown"))
        assertNull(Extensions.configValues(config, obj("""{"greeting":"a\nb"}""")))
    }

    @Test
    fun `json results`() {
        assertEquals(true to "Successfully disabled plugin: hello (scope: user)",
            Extensions.jsonResult(0, """{"command":"disable","outcome":"ok","message":"Successfully disabled plugin: hello (scope: user)"}"""))
        assertFalse(Extensions.jsonResult(1, """{"command":"install","outcome":"failed","message":"Plugin \"other\" not found"}""").first)
    }
}
