package dev.metaclaude.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session lines shaped like claude 2.1.316's (a prompt sent with our own uuid, attachments chained after it). */
class CheckpointsTest {
    private val p1 = "11111111-2222-4333-8444-555555555555"
    private val a1 = "3e2160ce-7035-4835-bfc0-2a4bfda07b8f"
    private val r1 = "d162e660-d66b-453a-b8ee-a5cdf8a2e4c0"
    private val p2 = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val lines = listOf(
        """{"type":"queue-operation","operation":"enqueue","content":"$p1"}""",
        """{"parentUuid":null,"isSidechain":false,"type":"user","uuid":"$p1","timestamp":"2026-11-01T21:41:05Z","message":{"role":"user","content":"add docs to utils.rs"}}""",
        """{"parentUuid":"$p1","isSidechain":false,"type":"attachment","uuid":"$a1","attachment":{}}""",
        """{"type":"file-history-snapshot","messageId":"$p1","snapshot":{}}""",
        """{"parentUuid":"$a1","isSidechain":false,"type":"assistant","uuid":"$r1","message":{"id":"msg_1","role":"assistant","content":[{"type":"text","text":"Done."}]}}""",
        """{"parentUuid":"$r1","isSidechain":false,"type":"user","uuid":"$p2","message":{"role":"user","content":[{"type":"text","text":"<ide_opened_file>x</ide_opened_file>"},{"type":"text","text":"now tests"}]}}""",
    )

    @Test
    fun `finds a prompt and the entry before it`() {
        val first = Checkpoints.prompt(lines.asSequence(), p1)!!
        assertNull(first.parentUuid)
        assertEquals("add docs to utils.rs", first.text)
        val second = Checkpoints.prompt(lines.asSequence(), p2)!!
        assertEquals(r1, second.parentUuid)
        assertEquals("now tests", second.text) // the IDE context block isn't part of what you typed
    }

    @Test
    fun `only your prompts are restore points`() {
        assertNull(Checkpoints.prompt(lines.asSequence(), r1)) // Claude's reply
        assertNull(Checkpoints.prompt(lines.asSequence(), a1)) // an attachment
        assertNull(Checkpoints.prompt(lines.asSequence(), "99999999-2222-4333-8444-555555555555"))
    }

    @Test
    fun `uuid check`() {
        assertTrue(Checkpoints.isUuid(p1))
        assertFalse(Checkpoints.isUuid("../../etc"))
        assertFalse(Checkpoints.isUuid("$p1 --rewind-files"))
        assertFalse(Checkpoints.isUuid(null))
    }

    @Test
    fun `fork arguments`() {
        assertEquals(listOf("--resume", "s1", "--fork-session", "--resume-session-at", r1), Checkpoints.forkArgs("s1", r1))
        assertEquals(emptyList<String>(), Checkpoints.forkArgs("s1", null)) // first prompt: a new conversation
    }

    @Test
    fun `rewind request and results`() {
        val req = Checkpoints.rewindRequest(p1, dryRun = true)
        assertEquals(p1, req.get("user_message_id").asString)
        assertTrue(req.get("dry_run").asBoolean)

        val ok = Checkpoints.result(JsonParser.parseString("""{"canRewind":true,"filesChanged":["src/utils.rs"],"insertions":3,"deletions":12}""").asJsonObject, null)
        assertTrue(ok.canRewind)
        assertEquals(listOf("src/utils.rs"), ok.files)
        assertEquals(3, ok.insertions)
        assertEquals(12, ok.deletions)
        val text = Checkpoints.confirmText(ok, "add docs to utils.rs", fork = false)
        assertTrue(text.contains("1 file will change (+3 −12)"))
        assertTrue(text.contains("src/utils.rs"))
        assertTrue(text.contains("Bash"))

        val none = Checkpoints.result(JsonParser.parseString("""{"canRewind":false,"error":"No file checkpoint found for this message"}""").asJsonObject, null)
        assertFalse(none.canRewind)
        assertEquals("No file checkpoint found for this message", none.error)

        val failed = Checkpoints.result(null, "Failed to rewind: disk full")
        assertFalse(failed.canRewind)
        assertEquals("Failed to rewind: disk full", failed.error)
    }

    @Test
    fun `fork transcript stops before the prompt and has no ids`() {
        val kept = SessionFiles.transcript(lines.asSequence(), until = p2, withUuids = false)
        assertEquals(listOf("historyUser", "textFinal"), kept.map { it.get("type").asString })
        assertFalse(kept[0].has("uuid"))
        val all = SessionFiles.transcript(lines.asSequence())
        assertEquals(p1, all[0].get("uuid").asString)
        assertEquals(p2, all.last { it.get("type").asString == "historyUser" }.get("uuid").asString)
    }
}
