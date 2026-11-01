package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Phase 12b: `/usage` and `/status` from the CLI's own answers (schema of claude 2.1.316):
 *
 *  - `get_usage` → {session{total_cost_usd, total_api_duration_ms, total_duration_ms, total_lines_added, total_lines_removed},
 *    subscription_type, rate_limits_available, rate_limits{five_hour, seven_day, seven_day_opus, seven_day_sonnet,
 *    model_scoped[{display_name, utilization, resets_at}], extra_usage}, behaviors{day, week: {request_count, session_count,
 *    behaviors[{key, pct, count}], recommendations[{key, pct, title, detail}], fix{keys, messages}, agents, skills, plugins,
 *    mcp_servers [{name, pct}]}}}. Marked experimental by the CLI: every field is optional here.
 *  - `/status`: the `initialize` answer's `account` {email, organization, subscriptionType, apiProvider},
 *    `get_binary_version` {version}, `get_settings` applied model / effort, `mcp_status` {mcpServers[{name, status}]}.
 *
 * Pure: unit-tested. The panel gets bounded plain text and numbers only.
 */
object UsageReport {
    val PERIODS = listOf("day", "week")

    private val WINDOWS = linkedMapOf(
        "five_hour" to "Current session (5 hours)",
        "seven_day" to "This week (all models)",
        "seven_day_opus" to "This week (Opus)",
        "seven_day_sonnet" to "This week (Sonnet)",
        "seven_day_oauth_apps" to "This week (apps)",
    )

    /** Plain words for the CLI's behavior keys (an unknown key is shown as it is). */
    fun behaviorLabel(key: String): String = when (key) {
        "cache_miss" -> "Prompt cache misses (long pauses between messages)"
        "long_context" -> "Long conversations"
        "subagent_heavy" -> "Heavy subagent use"
        "high_parallel" -> "Many sessions at once"
        "cron" -> "Scheduled runs"
        "max_effort" -> "Max effort"
        "workflow" -> "Workflows"
        "always_on_context" -> "Always-loaded context (CLAUDE.md, MCP tools)"
        "large_tool_results" -> "Large tool results"
        else -> key.take(60)
    }

    fun usage(p: JsonObject?): JsonObject = JsonObject().apply {
        addProperty("type", "usageReport")
        if (p == null) return@apply
        p.obj("session")?.let { s ->
            add("session", JsonObject().apply {
                addProperty("costUsd", s.num("total_cost_usd"))
                addProperty("apiMs", s.num("total_api_duration_ms").toLong())
                addProperty("wallMs", s.num("total_duration_ms").toLong())
                addProperty("linesAdded", s.num("total_lines_added").toLong())
                addProperty("linesRemoved", s.num("total_lines_removed").toLong())
            })
        }
        p.str("subscription_type")?.let { addProperty("plan", it.take(40)) }
        addProperty("limitsAvailable", p.isTrue("rate_limits_available"))
        val limits = JsonArray()
        p.obj("rate_limits")?.let { rl ->
            WINDOWS.forEach { (key, label) -> rl.obj(key)?.let { w -> window(label, w)?.let(limits::add) } }
            (rl.get("model_scoped") as? JsonArray)?.take(10)?.forEach { el ->
                val w = el as? JsonObject ?: return@forEach
                window("This week (${(w.str("display_name") ?: "model").take(40)})", w)?.let(limits::add)
            }
            rl.obj("extra_usage")?.takeIf { it.isTrue("is_enabled") }?.let { x ->
                add("extraUsage", JsonObject().apply {
                    x.get("utilization")?.takeIf { it.isJsonPrimitive }?.let { addProperty("utilization", it.asDouble) }
                    x.get("used_credits")?.takeIf { it.isJsonPrimitive }?.let { addProperty("used", it.asDouble) }
                    x.get("monthly_limit")?.takeIf { it.isJsonPrimitive }?.let { addProperty("limit", it.asDouble) }
                    x.str("currency")?.let { addProperty("currency", it.take(8)) }
                })
            }
        }
        add("limits", limits)
        p.obj("behaviors")?.let { b ->
            add("periods", JsonObject().apply { PERIODS.forEach { period -> b.obj(period)?.let { add(period, period(it)) } } })
        }
    }

    private fun window(label: String, w: JsonObject): JsonObject? {
        val util = w.get("utilization")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
        return JsonObject().apply {
            addProperty("label", label)
            addProperty("utilization", util.coerceIn(0.0, 100.0))
            w.str("resets_at")?.let { addProperty("resetsAt", it.take(40)) }
        }
    }

    private fun period(w: JsonObject): JsonObject = JsonObject().apply {
        addProperty("requests", w.num("request_count").toLong())
        addProperty("sessions", w.num("session_count").toLong())
        add("behaviors", JsonArray().apply {
            w.objects("behaviors").take(12).forEach { b ->
                val key = b.str("key") ?: return@forEach
                add(JsonObject().apply { addProperty("label", behaviorLabel(key)); addProperty("pct", b.num("pct")); addProperty("count", b.num("count").toLong()) })
            }
        })
        add("recommendations", JsonArray().apply {
            w.objects("recommendations").take(10).forEach { r ->
                add(JsonObject().apply {
                    addProperty("title", (r.str("title") ?: "").take(200))
                    r.str("detail")?.let { addProperty("detail", it.take(300)) }
                    addProperty("pct", r.num("pct"))
                })
            }
        })
        addProperty("canFix", fixMessages(w).isNotEmpty())
        add("attribution", JsonArray().apply {
            listOf("agents" to "Agent", "skills" to "Skill", "plugins" to "Plugin", "mcp_servers" to "MCP server").forEach { (key, kind) ->
                w.objects(key).take(5).forEach { a ->
                    add(JsonObject().apply { addProperty("kind", kind); addProperty("name", (a.str("name") ?: "").take(80)); addProperty("pct", a.num("pct")) })
                }
            }
        })
    }

    /** The messages "Fix" sends for one period, in order (the CLI words them: /effort first, then one prompt). */
    fun fixMessages(usage: JsonObject?, period: String): List<String> =
        usage?.obj("behaviors")?.obj(period)?.let { fixMessages(it) } ?: emptyList()

    private fun fixMessages(w: JsonObject): List<String> =
        (w.obj("fix")?.get("messages") as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.take(4000) }
            ?.filter { it.isNotBlank() }?.take(5) ?: emptyList()

    /** `/status` card: what is running and who is signed in. Any part may be missing (old CLI, no session yet). */
    fun status(pluginVersion: String, ideBuild: String, account: JsonObject?, version: JsonObject?, settings: JsonObject?, mcp: JsonObject?, cwd: String?): JsonObject =
        JsonObject().apply {
            addProperty("type", "statusReport")
            val rows = JsonArray()
            fun row(label: String, value: String?) { if (!value.isNullOrBlank()) rows.add(JsonObject().apply { addProperty("label", label); addProperty("value", value.take(200)) }) }
            row("Clawed UI", pluginVersion)
            row("IDE build", ideBuild)
            row("Claude Code", version?.str("version"))
            row("Folder", cwd)
            account?.let { a ->
                row("Account", a.str("email"))
                row("Organization", a.str("organization"))
                row("Plan", a.str("subscriptionType"))
                row("Signed in with", a.str("tokenSource") ?: a.str("apiKeySource"))
                a.str("apiProvider")?.takeIf { it != "firstParty" }?.let { row("API provider", it) }
            }
            val applied = settings?.obj("applied")
            row("Model", applied?.str("model"))
            row("Effort", applied?.str("effort"))
            add("rows", rows)
            add("mcp", JsonArray().apply {
                (mcp?.get("mcpServers") as? JsonArray)?.take(50)?.forEach { el ->
                    val s = el as? JsonObject ?: return@forEach
                    add(JsonObject().apply { addProperty("name", (s.str("name") ?: "?").take(80)); addProperty("status", (s.str("status") ?: "").take(40)) })
                }
            })
        }

    private fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.isTrue(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    private fun JsonObject.num(key: String): Double = (get(key) as? JsonElement)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0
}
