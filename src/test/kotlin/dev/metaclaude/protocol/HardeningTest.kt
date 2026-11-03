package dev.metaclaude.protocol

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class HardeningTest {
    @Test fun versionParsing() {
        assertEquals(listOf(2, 1, 316), CliVersion.parse("2.1.316 (Claude Code)"))
        assertNull(CliVersion.parse("unknown"))
        assertNull(CliVersion.parse(null))
    }

    @Test fun versionNotices() {
        assertNull(CliVersion.notice("2.1.316"))
        assertNull(CliVersion.notice("2.1.400"))
        assertNull(CliVersion.notice("2.9.0"))
        assertNull(CliVersion.notice("garbage")) // unknown: never guess
        assertTrue(CliVersion.notice("2.1.200")!!.contains("claude update"))
        assertTrue(CliVersion.notice("1.0.99")!!.startsWith("Your Claude Code is 1.0.99"))
        assertTrue(CliVersion.notice("3.0.0")!!.contains("new major version"))
    }

    @Test fun unsupportedRequestBecomesAdvice() {
        assertEquals(
            "your Claude Code doesn't support this yet (get_usage): run `claude update`, then start a new conversation",
            CliVersion.friendlyError("Unsupported control request subtype: get_usage"),
        )
        assertEquals("rate limited", CliVersion.friendlyError("rate limited"))
        assertFalse(CliVersion.friendlyError("Unsupported control request subtype: <b>x</b>").contains("<b>"))
    }

    @Test fun sweepRemovesOnlyDeadOwners() {
        val dir = Files.createTempDirectory("pf")
        val dead = PrivateFiles.create("mcp", "{}", dir, pid = 111)
        val live = PrivateFiles.create("settings", "{}", dir, pid = 222)
        val other = Files.writeString(dir.resolve("notes.txt"), "x")
        assertEquals(1, PrivateFiles.sweep(dir) { it == 222L })
        assertFalse(Files.exists(dead))
        assertTrue(Files.exists(live))
        assertTrue(Files.exists(other))
    }

    @Test fun legacySweepWaitsADay() {
        val tmp = Files.createTempDirectory("legacy")
        val now = Instant.now()
        val old = Files.writeString(tmp.resolve("claude-ui-mcp123.json"), "{}")
        Files.setLastModifiedTime(old, FileTime.from(now - Duration.ofDays(2)))
        val fresh = Files.writeString(tmp.resolve("clawed-ui-settings9.json"), "{}")
        val unrelated = Files.writeString(tmp.resolve("other.json"), "{}")
        Files.setLastModifiedTime(unrelated, FileTime.from(now - Duration.ofDays(9)))
        assertEquals(1, PrivateFiles.sweepLegacy(tmp, now))
        assertFalse(Files.exists(old)); assertTrue(Files.exists(fresh)); assertTrue(Files.exists(unrelated))
    }
}
