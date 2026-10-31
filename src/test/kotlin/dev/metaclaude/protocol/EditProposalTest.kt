package dev.metaclaude.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditProposalTest {
    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `edit replaces the first match, or all with replace_all`() {
        val e = obj("""{"file_path":"a.rs","old_string":"x","new_string":"y"}""")
        assertEquals("y x", EditProposal.proposed("Edit", e, "x x"))
        e.addProperty("replace_all", true)
        assertEquals("y y", EditProposal.proposed("Edit", e, "x x"))
    }

    @Test
    fun `crlf files are compared with lf, missing text gives null`() {
        val e = obj("""{"file_path":"a.rs","old_string":"a\r\nb","new_string":"c"}""")
        assertEquals("c\nz", EditProposal.proposed("Edit", e, "a\r\nb\r\nz"))
        assertNull(EditProposal.proposed("Edit", obj("""{"old_string":"nope","new_string":"q"}"""), "abc"))
    }

    @Test
    fun `multi edit applies in order and write takes the content`() {
        val m = obj("""{"edits":[{"old_string":"a","new_string":"b"},{"old_string":"b","new_string":"c"}]}""")
        assertEquals("c", EditProposal.proposed("MultiEdit", m, "a"))
        assertEquals("new\ntext", EditProposal.proposed("Write", obj("""{"content":"new\r\ntext"}"""), "old"))
    }

    @Test
    fun `user edits go back as a whole-file replacement in the file's line ends`() {
        val e = obj("""{"file_path":"a.rs","old_string":"x","new_string":"y"}""")
        val out = EditProposal.editedInput("Edit", e, "x\r\nz", "mine\nz")
        assertEquals("x\r\nz", out.get("old_string").asString)
        assertEquals("mine\r\nz", out.get("new_string").asString)
        assertEquals("a.rs", out.get("file_path").asString)
        assertEquals("y", e.get("new_string").asString) // original untouched
        val w = EditProposal.editedInput("Write", obj("""{"file_path":"b","content":"p"}"""), "", "mine")
        assertEquals("mine", w.get("content").asString)
        val m = EditProposal.editedInput("MultiEdit", obj("""{"file_path":"c","edits":[]}"""), "old", "new")
        assertEquals("old", m.getAsJsonArray("edits")[0].asJsonObject.get("old_string").asString)
    }
}
