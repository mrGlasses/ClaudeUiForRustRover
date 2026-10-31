package dev.metaclaude.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Requests shaped like the spike logs (CLI 2.1.312) and the Agent SDK user-input docs. */
class PermissionsTest {
    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private val write = obj("""{"subtype":"can_use_tool","tool_name":"Write","display_name":"Write","input":{"file_path":"C:\\tmp\\a.txt","content":"hello"},
        "description":"~\\tmp\\a.txt","permission_suggestions":[{"type":"setMode","mode":"acceptEdits","destination":"session"},
        {"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"npm test"}],"behavior":"allow","destination":"localSettings"}],
        "decision_reason":"Path is outside allowed working directories","tool_use_id":"toolu_1"}""")

    @Test
    fun `card shows tool, preview and labelled suggestions`() {
        val c = Permissions.card("req-1", write)
        assertEquals("permission", c.get("type").asString)
        assertEquals("tool", c.get("kind").asString)
        assertEquals("C:\\tmp\\a.txt\nhello", c.get("preview").asString)
        val s = c.getAsJsonArray("suggestions").map { it.asJsonObject }
        assertEquals("Yes, and switch to Edit automatically", s[0].get("label").asString)
        assertEquals("Yes, and don't ask again for Bash(npm test)", s[1].get("label").asString)
        assertFalse(s[0].get("canChangeDestination").asBoolean)
        assertTrue(s[1].get("canChangeDestination").asBoolean)
    }

    @Test
    fun `allow echoes the input and the chosen suggestion`() {
        val input = write.getAsJsonObject("input")
        val a = Permissions.allow(input, write.getAsJsonArray("permission_suggestions")[0].asJsonObject)
        assertEquals("allow", a.get("behavior").asString)
        assertEquals(input, a.get("updatedInput"))
        assertEquals("setMode", a.getAsJsonArray("updatedPermissions")[0].asJsonObject.get("type").asString)
        assertNull(Permissions.allow(input).get("updatedPermissions"))
    }

    @Test
    fun `save-to destination only changes addRules, only to known places`() {
        val rule = write.getAsJsonArray("permission_suggestions")[1].asJsonObject
        assertEquals("userSettings", Permissions.withDestination(rule, "userSettings").get("destination").asString)
        assertEquals("localSettings", Permissions.withDestination(rule, "/etc/evil").get("destination").asString)
        val mode = write.getAsJsonArray("permission_suggestions")[0].asJsonObject
        assertEquals("session", Permissions.withDestination(mode, "userSettings").get("destination").asString)
        assertTrue(Permissions.isPersistent(rule))
        assertFalse(Permissions.isPersistent(mode))
    }

    @Test
    fun `question answers are checked against the real questions`() {
        val input = obj("""{"questions":[{"question":"Format?","header":"Format","options":[{"label":"Summary","description":"short"},{"label":"Detailed","description":"long"}],"multiSelect":false},
            {"question":"Sections?","header":"Sections","options":[{"label":"Intro","description":""},{"label":"End","description":""}],"multiSelect":true}]}""")
        val card = Permissions.card("q", obj("""{"tool_name":"AskUserQuestion","input":$input}"""))
        assertEquals("question", card.get("kind").asString)
        assertEquals(2, card.getAsJsonArray("questions").size())
        val out = Permissions.answeredInput(input, obj("""{"Format?":"Summary","Sections?":["Intro","End"],"Injected?":"x"}"""), null)!!
        assertEquals(input.get("questions"), out.get("questions"))
        assertEquals("Summary", out.getAsJsonObject("answers").get("Format?").asString)
        assertEquals(2, out.getAsJsonObject("answers").getAsJsonArray("Sections?").size())
        assertNull(out.getAsJsonObject("answers").get("Injected?"))
        assertNull(Permissions.answeredInput(input, obj("""{"Format?":"  "}"""), null))
        assertEquals("do X", Permissions.answeredInput(input, null, "do X")!!.get("response").asString)
    }

    @Test
    fun `plan card carries the plan and deny carries the message`() {
        val c = Permissions.card("p", obj("""{"tool_name":"ExitPlanMode","input":{"plan":"# Plan\n1. do it"}}"""))
        assertEquals("plan", c.get("kind").asString)
        assertEquals("# Plan\n1. do it", c.get("plan").asString)
        val d = Permissions.deny("no")
        assertEquals("deny", d.get("behavior").asString)
        assertEquals("no", d.get("message").asString)
    }
}
