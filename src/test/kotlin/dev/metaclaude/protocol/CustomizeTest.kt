package dev.metaclaude.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers shaped like claude 2.1.316's initialize commands and get_skills_dialog. */
class CustomizeTest {
    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    private val skills = obj("""{"skills":[
        {"name":"pdf","display_name":"pdf","description":"Read PDFs","source":"user","tokens":120,"state":"on","advertised":true,"handles":{"unqualified_name":"pdf"}},
        {"name":"deploy","display_name":"deploy","description":"Ship it","source":"plugin","tokens":80,"state":"on","locked_by":"plugin","advertised":true,"handles":{}},
        {"name":"odd","display_name":"odd","description":"","source":"project","tokens":5,"state":"weird","advertised":false,"handles":{}}]}""")

    @Test
    fun `commands keep name, hint and description`() {
        val c = Customize.commands(JsonParser.parseString("""[{"name":"review","description":"Review a PR","argumentHint":"<pr>","aliases":["rv"],"builtin":true},{"name":""},{"description":"no name"}]""").asJsonArray)
        assertEquals(1, c.size())
        val row = c[0].asJsonObject
        assertEquals("review", row.get("name").asString)
        assertEquals("<pr>", row.get("argumentHint").asString)
        assertTrue(row.get("builtin").asBoolean)
        assertEquals("rv", row.getAsJsonArray("aliases")[0].asString)
    }

    @Test
    fun `skills rows`() {
        val rows = Customize.skills(skills)
        assertEquals(3, rows.size())
        assertEquals("plugin", rows[1].asJsonObject.get("lockedBy").asString)
        assertEquals("on", rows[2].asJsonObject.get("state").asString) // unknown state shown as the default
    }

    @Test
    fun `skill edits only for listed, unlocked skills and known states`() {
        val edit = Customize.skillEdit(skills, "pdf", "off")!!
        assertEquals("pdf", edit.get("name").asString)
        assertEquals("off", edit.get("state").asString)
        assertEquals("pdf", edit.getAsJsonObject("handles").get("unqualified_name").asString)
        assertNull(Customize.skillEdit(skills, "deploy", "off")) // locked by its plugin
        assertNull(Customize.skillEdit(skills, "pdf", "sometimes"))
        assertNull(Customize.skillEdit(skills, "rm -rf", "off"))
        assertNull(Customize.skillEdit(null, "pdf", "off"))
    }

    @Test
    fun `output style requests and instruction files`() {
        val req = Customize.outputStyleRequest("Explanatory", listOf("default", "Explanatory", "Learning"))!!
        assertEquals("localSettings", req.get("source").asString)
        assertEquals("Explanatory", req.getAsJsonObject("settings").get("outputStyle").asString)
        assertNull(Customize.outputStyleRequest("Pirate", listOf("default")))

        val files = Customize.instructionFiles("C:\\Users\\Nick", "C:\\p\\", "\\")
        assertEquals("C:\\p\\CLAUDE.md", files[0].path)
        assertEquals("C:\\p\\.claude\\CLAUDE.md", files[1].path)
        assertEquals("C:\\p\\CLAUDE.local.md", files[2].path)
        assertEquals("C:\\Users\\Nick\\.claude\\CLAUDE.md", files[3].path)
        assertFalse(files.any { it.path.contains("\\\\") })
    }

    @Test
    fun `commands_changed reaches the panel`() {
        val m = StreamEvents.toPanel(obj("""{"type":"system","subtype":"commands_changed","commands":[{"name":"fix","description":"Fix it","argumentHint":""}],"uuid":"u","session_id":"s"}""")).single()
        assertEquals("commandsChanged", m.get("type").asString)
        assertEquals("fix", m.getAsJsonArray("commands")[0].asJsonObject.get("name").asString)
    }
}
