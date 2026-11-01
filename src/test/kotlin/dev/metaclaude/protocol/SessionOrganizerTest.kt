package dev.metaclaude.protocol

import dev.metaclaude.protocol.SessionOrganizer.Filter
import dev.metaclaude.protocol.SessionOrganizer.Notes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOrganizerTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private val sessions = listOf(
        SessionFiles.Summary("a", "First", now - 1 * day, 3, null),
        SessionFiles.Summary("b", "Second", now - 10 * day, 5, null),
        SessionFiles.Summary("c", "Third", now - 20 * day, 1, null),
        SessionFiles.Summary("d", "Fourth", now - 2 * day, 2, null),
    )

    @Test
    fun `active hides archived, auto-archive by idle days, unarchive wins`() {
        val notes = Notes(archived = setOf("d"), unarchived = setOf("c"))
        val active = SessionOrganizer.view(sessions, notes, emptySet(), Filter.ACTIVE, now, 7).map { it.id }
        assertEquals(listOf("a", "c"), active) // b idle 10 days > 7 (auto), d archived by you, c brought back
        val archived = SessionOrganizer.view(sessions, notes, emptySet(), Filter.ARCHIVED, now, 7)
        assertEquals(listOf("d", "b"), archived.map { it.id })
        assertTrue(archived.first { it.id == "b" }.autoArchived)
        assertEquals(3, SessionOrganizer.view(sessions, notes, emptySet(), Filter.ACTIVE, now, 0).size) // no auto-archive: only d is archived
    }

    @Test
    fun `renames, open tabs and groups`() {
        val notes = Notes(titles = mapOf("b" to "Renamed"), groupOf = mapOf("c" to "Bugs", "a" to "Gone"), groups = listOf("Bugs"))
        val v = SessionOrganizer.view(sessions, notes, setOf("b"), Filter.ALL, now, 0)
        assertEquals(listOf("c", "a", "d", "b"), v.map { it.id }) // group "Bugs" first; "Gone" isn't a group any more
        assertEquals("Renamed", v.first { it.id == "b" }.title)
        assertNull(v.first { it.id == "a" }.group)
        assertEquals(listOf("b"), SessionOrganizer.view(sessions, notes, setOf("b"), Filter.OPEN, now, 0).map { it.id })
    }

    @Test
    fun `names are one trimmed line`() {
        assertEquals("Fix login", SessionOrganizer.cleanName("  Fix login \nsecond line"))
        assertNull(SessionOrganizer.cleanName("   "))
        assertEquals(SessionOrganizer.NAME_MAX, SessionOrganizer.cleanName("x".repeat(500))!!.length)
    }
}
