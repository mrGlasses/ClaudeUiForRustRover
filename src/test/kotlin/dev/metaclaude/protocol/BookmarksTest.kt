package dev.metaclaude.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarksTest {
    @Test
    fun `bookmarks round trip, newest first`() {
        val a = Bookmarks.make("1", "s1", "Fix the parser", "Use `?`.", 10L)
        val b = Bookmarks.make("2", "s2", "x".repeat(500), "y".repeat(30_000), 20L)
        assertEquals("1", Bookmarks.idOf(a))
        val list = Bookmarks.list(listOf(a, b))
        assertEquals("2", list[0].asJsonObject.get("id").asString)
        assertEquals(120, list[0].asJsonObject.get("title").asString.length)
        assertEquals(Bookmarks.TEXT_MAX, list[0].asJsonObject.get("text").asString.length)
        assertEquals("s1", Bookmarks.find(listOf(a, b), "1")!!.get("session").asString)
        assertNull(Bookmarks.find(listOf(a, "not json"), "9"))
    }

    @Test
    fun `side questions carry the newest 20 exchanges`() {
        val history = (1..25).map { "q$it" to "a$it" }
        val body = Bookmarks.sideQuestion("why?", history)
        assertEquals("why?", body.get("question").asString)
        val h = body.getAsJsonArray("history")
        assertEquals(20, h.size())
        assertEquals("q6", h[0].asJsonObject.get("question").asString)
        assertEquals("a25", h[19].asJsonObject.get("response").asString)
        assertEquals("Because.", Bookmarks.sideAnswer(JsonParser.parseString("""{"response":"Because.","synthetic":false}""").asJsonObject))
        assertNull(Bookmarks.sideAnswer(JsonParser.parseString("""{"response":null}""").asJsonObject))
    }
}
