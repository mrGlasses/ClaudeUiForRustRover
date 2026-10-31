package dev.metaclaude.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines shaped like Claude Code session files (see SessionFiles). */
class SessionFilesTest {
    private val lines = listOf(
        """{"type":"file-history-snapshot","messageId":"x"}""",
        """{"type":"user","uuid":"1","parentUuid":null,"sessionId":"s","cwd":"C:\\p","timestamp":"2026-10-31T10:00:00Z","isSidechain":false,"message":{"role":"user","content":[{"type":"text","text":"<ide_opened_file>The user opened the file a.rs</ide_opened_file>"},{"type":"text","text":"fix the failing test"}]}}""",
        """{"type":"assistant","uuid":"2","parentUuid":"1","isSidechain":false,"message":{"id":"msg_1","role":"assistant","content":[{"type":"text","text":"Looking."}]}}""",
        """{"type":"assistant","uuid":"3","parentUuid":"2","isSidechain":false,"message":{"id":"msg_1","role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"a.rs"}}]}}""",
        """{"type":"user","uuid":"4","parentUuid":"3","isSidechain":false,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"fn a() {}"}]},"toolUseResult":{}}""",
        """{"type":"assistant","uuid":"5","parentUuid":"4","isSidechain":false,"message":{"id":"msg_1","role":"assistant","content":[{"type":"text","text":"Fixed."}]}}""",
        """{"type":"user","uuid":"6","isMeta":true,"message":{"role":"user","content":"<command-name>/clear</command-name>"}}""",
        """{"type":"user","uuid":"7","parentUuid":"5","isSidechain":false,"message":{"role":"user","content":"thanks"}}""",
        "not json",
    )

    @Test
    fun `summary uses the first prompt, counts prompts and finds cwd`() {
        val s = SessionFiles.summarize("abc", lines.asSequence(), 5L)!!
        assertEquals("fix the failing test", s.title)
        assertEquals(2, s.prompts)
        assertEquals("C:\\p", s.cwd)
    }

    @Test
    fun `custom title wins, then generated`() {
        val withTitles = lines + """{"type":"summary","summary":"Fix test","leafUuid":"7"}""" + """{"type":"custom-title","customTitle":"My fix","sessionId":"s"}"""
        assertEquals("My fix", SessionFiles.summarize("abc", withTitles.asSequence(), 0L)!!.title)
        assertEquals("Fix test", SessionFiles.summarize("abc", (lines + """{"type":"summary","summary":"Fix test"}""").asSequence(), 0L)!!.title)
        assertNull(SessionFiles.summarize("abc", sequenceOf("""{"type":"summary","summary":"x"}"""), 0L))
    }

    @Test
    fun `transcript replays prompts, text and tool cards with unique keys`() {
        val t = SessionFiles.transcript(lines.asSequence())
        assertEquals(listOf("historyUser", "textFinal", "toolUse", "toolResult", "textFinal", "historyUser"), t.map { it.get("type").asString })
        assertEquals("fix the failing test", t[0].get("text").asString)
        assertTrue(t[1].get("key").asString != t[4].get("key").asString)
    }

    @Test
    fun `export, ids and folder names`() {
        val md = SessionFiles.markdown("T", lines.asSequence())
        assertTrue(md.contains("## You\n\nfix the failing test"))
        assertTrue(md.contains("> Read: a.rs"))
        assertFalse(md.contains("ide_opened_file"))
        assertEquals("C--Users-Nick-Documents-claude-meta-plugin", SessionFiles.projectDirName("C:\\Users\\Nick\\Documents\\claude-meta-plugin"))
        assertTrue(SessionFiles.isSessionId("1f0e2d3c-aaaa-bbbb-cccc-1234567890ab"))
        assertFalse(SessionFiles.isSessionId("../../etc"))
    }
}
