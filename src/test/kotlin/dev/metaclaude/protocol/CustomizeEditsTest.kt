package dev.metaclaude.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers shaped like claude 2.1.316's get_hooks_listing, list_permission_rules and get_memory_dialog. */
class CustomizeEditsTest {
    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    private val hooks = obj("""{"events":[],"eventCatalog":[{"name":"PostToolUse","summary":"After a tool","supportsMatcher":true},{"name":"Stop","summary":"When Claude stops","supportsMatcher":false}],
      "hooks":[
        {"event":"PostToolUse","matcher":"Edit|Write","source":"localSettings","sourceLabel":"Local","type":"command","displayText":"cargo fmt","commandText":"cargo fmt","contentLabel":"Command","timeout":30,
         "editable":{"matcher":"Edit|Write","config":{"type":"command","command":"cargo fmt","timeout":30}}},
        {"event":"Stop","matcher":"","source":"pluginHook","sourceLabel":"Plugin","type":"command","displayText":"x","commandText":"x","contentLabel":"Command"},
        {"event":"Stop","matcher":"","source":"userSettings","sourceLabel":"User","type":"prompt","displayText":"p","commandText":"Check","contentLabel":"Prompt",
         "editable":{"matcher":"","config":{"type":"prompt","prompt":"Check"}}}],
      "policy":{"disabledByPolicy":false,"managedOnly":false,"pluginOnly":false,"allDisabled":false,"policyHookCount":0}}""")

    @Test
    fun `hooks listing for the panel`() {
        val h = CustomizeEdits.hooks(hooks)
        val rows = h.getAsJsonArray("hooks").map { it.asJsonObject }
        assertTrue(rows[0].get("formEditable").asBoolean)
        assertFalse(rows[1].get("editable").asBoolean) // plugin hook: read-only
        assertTrue(rows[2].get("editable").asBoolean)
        assertFalse(rows[2].get("formEditable").asBoolean) // prompt hook: remove only
        assertFalse(h.get("locked").asBoolean)
    }

    @Test
    fun `hook edits are built from the listing`() {
        val add = CustomizeEdits.hookEdit(hooks, "add", null, "projectSettings", "Stop", "ignored", "cargo clippy", 60)!!
        assertEquals("", add.get("matcher").asString) // Stop takes no matcher
        assertEquals("cargo clippy", add.getAsJsonObject("hook").get("command").asString)
        assertEquals(60, add.getAsJsonObject("hook").get("timeout").asInt)

        val replace = CustomizeEdits.hookEdit(hooks, "replace", 0, null, "PostToolUse", "Edit", "cargo fmt --all", null)!!
        assertEquals("localSettings", replace.get("source").asString)
        assertEquals("Edit|Write", replace.getAsJsonObject("target").get("matcher").asString)
        assertEquals("cargo fmt", replace.getAsJsonObject("target").getAsJsonObject("hook").get("command").asString)
        assertFalse(replace.getAsJsonObject("hook").has("timeout")) // cleared

        val remove = CustomizeEdits.hookEdit(hooks, "remove", 2, null, null, null, null, null)!!
        assertEquals("userSettings", remove.get("source").asString)
        assertEquals("Check", remove.getAsJsonObject("target").getAsJsonObject("hook").get("prompt").asString)

        assertNull(CustomizeEdits.hookEdit(hooks, "remove", 1, null, null, null, null, null)) // plugin hook
        assertNull(CustomizeEdits.hookEdit(hooks, "replace", 2, null, "Stop", "", "x", null)) // not a command hook
        assertNull(CustomizeEdits.hookEdit(hooks, "add", null, "policySettings", "Stop", "", "x", null))
        assertNull(CustomizeEdits.hookEdit(hooks, "add", null, "localSettings", "Nope", "", "x", null))
        assertNull(CustomizeEdits.hookEdit(hooks, "add", null, "localSettings", "Stop", "", "  ", null))
    }

    private val rules = obj("""{"state":{"rules":[
        {"behavior":"allow","source":"localSettings","rule":"Bash(cargo test:*)","description":{"prefix":"Any Bash command starting with ","emphasis":"cargo test"},"editability":"persistent"},
        {"behavior":"deny","source":"policySettings","rule":"WebFetch","editability":"readonly"},
        {"behavior":"ask","source":"session","rule":"Edit","editability":"session"}],
      "workspaceDirectories":[{"path":"C:\\other","source":"cliArg"}],"originalCwd":"C:\\p","managedOnly":false}}""")

    @Test
    fun `permission rules`() {
        val p = CustomizeEdits.permissions(rules)
        val rows = p.getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals("Any Bash command starting with cargo test", rows[0].get("description").asString)
        assertTrue(rows[0].get("removable").asBoolean)
        assertFalse(rows[1].get("removable").asBoolean)
        assertFalse(rows[2].get("removable").asBoolean)

        val remove = CustomizeEdits.permissionRemove(rules, 0)!!
        assertEquals("Bash(cargo test:*)", remove.get("rule").asString)
        assertEquals("localSettings", remove.get("source").asString)
        assertNull(CustomizeEdits.permissionRemove(rules, 1))
        assertNull(CustomizeEdits.permissionRemove(rules, 9))

        val add = CustomizeEdits.permissionAdd("Read(./src/**)\n\n  Bash(cargo build:*) \nRead(./src/**)", "allow", "projectSettings")!!
        assertEquals(listOf("Read(./src/**)", "Bash(cargo build:*)"), add.getAsJsonArray("rules").map { it.asString })
        assertNull(CustomizeEdits.permissionAdd("Read", "always", "localSettings"))
        assertNull(CustomizeEdits.permissionAdd("Read", "allow", "policySettings"))
        assertNull(CustomizeEdits.permissionAdd("  \n ", "allow", "localSettings"))
    }

    private val memory = obj("""{"files":[{"kind":"user","path":"C:\\Users\\Nick\\.claude\\CLAUDE.md","label":"User memory","description":"","exists":false},
        {"kind":"local","path":"C:\\p\\CLAUDE.local.md","label":"Local","description":"","exists":false}],
      "folders":[{"kind":"auto","path":"C:\\Users\\Nick\\.claude\\projects\\p\\memory","label":"Auto-memory folder","description":""}],
      "memories":[{"name":"MEMORY.md","path":"C:\\Users\\Nick\\.claude\\projects\\p\\memory\\MEMORY.md","description":null,"type":null,"modified_ms":1}],
      "auto_memory":{"enabled":true,"toggleable":true,"status":"on"},"auto_dream":{"shown":false,"enabled":false,"toggleable":false,"status":"","detail":""}}""")

    @Test
    fun `memory listing, paths and edits`() {
        val m = CustomizeEdits.memory(memory)
        assertTrue(m.getAsJsonObject("autoMemory").get("enabled").asBoolean)
        assertFalse(m.has("autoDream")) // not shown
        assertEquals("MEMORY.md", m.getAsJsonArray("memories")[0].asJsonObject.get("name").asString)

        assertEquals("C:\\Users\\Nick\\.claude\\CLAUDE.md" to true, CustomizeEdits.memoryPath(memory, "file", 0))
        assertEquals(false, CustomizeEdits.memoryPath(memory, "file", 1)!!.second) // only user / project files may be created
        assertEquals("C:\\Users\\Nick\\.claude\\projects\\p\\memory\\MEMORY.md", CustomizeEdits.memoryPath(memory, "memory", 0)!!.first)
        assertNull(CustomizeEdits.memoryPath(memory, "secrets", 0))
        assertNull(CustomizeEdits.memoryPath(memory, "folder", 3))

        assertFalse(CustomizeEdits.memoryEdit("autoMemory", false)!!.get("autoMemoryEnabled").asBoolean)
        assertNull(CustomizeEdits.memoryEdit("everything", true))
    }

    @Test
    fun `cli results`() {
        assertEquals(true to "", CustomizeEdits.cliResult(0, "{\"ok\":true,\"written\":{}}\n"))
        assertEquals(true to "saved as the tool-wide rule", CustomizeEdits.cliResult(0, "{\"ok\":true,\"warnings\":[\"saved as the tool-wide rule\"],\"stored\":[]}"))
        assertEquals(false to "edit_hook: an identical hook is already configured", CustomizeEdits.cliResult(1, "edit_hook: an identical hook is already configured\n"))
        assertFalse(CustomizeEdits.cliResult(0, "{\"ok\":false,\"error\":\"nope\"}").first)
    }
}
