package dev.metaclaude.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers shaped like claude 2.1.316's get_usage, initialize.account, get_binary_version and mcp_status. */
class UsageReportTest {
    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    private val usage = obj("""{
      "session":{"total_cost_usd":0.4213,"total_api_duration_ms":61000,"total_duration_ms":300000,"total_lines_added":12,"total_lines_removed":3,"model_usage":{}},
      "subscription_type":"max","rate_limits_available":true,
      "rate_limits":{"five_hour":{"utilization":48,"resets_at":"2026-11-01T23:00:00Z"},"seven_day":{"utilization":51,"resets_at":"2026-11-05T10:00:00Z"},
        "seven_day_opus":null,"model_scoped":[{"display_name":"Fable","utilization":12,"resets_at":null}],
        "extra_usage":{"is_enabled":false,"monthly_limit":null,"used_credits":null,"utilization":null}},
      "behaviors":{"day":{"request_count":120,"session_count":4,
        "behaviors":[{"key":"cache_miss","pct":35,"count":40},{"key":"brand_new","pct":5,"count":2}],
        "recommendations":[{"key":"cache_miss","pct":35,"title":"Keep sessions warm","detail":"40 cache misses"}],
        "fix":{"keys":["max_effort"],"messages":["/effort high","Please keep tool output short"]},
        "agents":[{"name":"Explore","pct":20}],"skills":[],"plugins":[],"mcp_servers":[{"name":"clawed-ui","pct":2}]},
        "week":{"request_count":900,"session_count":30,"behaviors":[],"recommendations":[],"agents":[],"skills":[],"plugins":[],"mcp_servers":[]}}}""")

    @Test
    fun `usage report for the panel`() {
        val u = UsageReport.usage(usage)
        assertEquals("max", u.get("plan").asString)
        val limits = u.getAsJsonArray("limits").map { it.asJsonObject }
        assertEquals(listOf("Current session (5 hours)", "This week (all models)", "This week (Fable)"), limits.map { it.get("label").asString })
        assertEquals(48.0, limits[0].get("utilization").asDouble, 1e-9)
        assertFalse(u.has("extraUsage")) // not enabled
        assertEquals(12L, u.getAsJsonObject("session").get("linesAdded").asLong)
        val day = u.getAsJsonObject("periods").getAsJsonObject("day")
        assertTrue(day.get("canFix").asBoolean)
        assertEquals("Prompt cache misses (long pauses between messages)", day.getAsJsonArray("behaviors")[0].asJsonObject.get("label").asString)
        assertEquals("brand_new", day.getAsJsonArray("behaviors")[1].asJsonObject.get("label").asString)
        assertEquals(2, day.getAsJsonArray("attribution").size())
        assertFalse(u.getAsJsonObject("periods").getAsJsonObject("week").get("canFix").asBoolean)
    }

    @Test
    fun `fix sends only the CLI's own messages`() {
        assertEquals(listOf("/effort high", "Please keep tool output short"), UsageReport.fixMessages(usage, "day"))
        assertEquals(emptyList<String>(), UsageReport.fixMessages(usage, "week"))
        assertEquals(emptyList<String>(), UsageReport.fixMessages(null, "day"))
    }

    @Test
    fun `empty or failed usage`() {
        val u = UsageReport.usage(null)
        assertEquals("usageReport", u.get("type").asString)
        assertFalse(u.has("limits"))
    }

    @Test
    fun `status rows`() {
        val s = UsageReport.status(
            "0.28.0", "RR-262.1",
            obj("""{"email":"nick@example.com","organization":"Nick's Org","subscriptionType":"max","apiProvider":"firstParty"}"""),
            obj("""{"version":"2.1.316"}"""),
            obj("""{"effective":{},"sources":[],"applied":{"model":"claude-opus-5-5","effort":"high"}}"""),
            obj("""{"mcpServers":[{"name":"clawed-ui","status":"connected","scope":"dynamic"}]}"""),
            "C:\\p",
        )
        val rows = s.getAsJsonArray("rows").associate { it.asJsonObject.get("label").asString to it.asJsonObject.get("value").asString }
        assertEquals("2.1.316", rows["Claude Code"])
        assertEquals("nick@example.com", rows["Account"])
        assertEquals("claude-opus-5-5", rows["Model"])
        assertFalse(rows.containsKey("API provider")) // first party: nothing to say
        assertEquals("connected", s.getAsJsonArray("mcp")[0].asJsonObject.get("status").asString)
    }
}
