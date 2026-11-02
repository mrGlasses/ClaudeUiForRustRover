package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Phase 13b: Hooks, Permissions and Memory tabs of the Customize view. Read through control requests, written through the
 * CLI's own `claude edit-<thing> --json` subcommands (one JSON edit on stdin), as the VS Code extension does (claude 2.1.316):
 *
 *  - Hooks: `get_hooks_listing` → {events, hooks: [{event, matcher, source, sourceLabel, type, displayText, commandText,
 *    contentLabel, timeout?, disabled?, editable?: {matcher, config}}], eventCatalog: [{name, summary, supportsMatcher}],
 *    policy{...}}; `claude edit-hook --json` with {op: add|replace|remove, source, event, matcher, hook?, target?}.
 *  - Permissions: `list_permission_rules` → {state: {rules: [{behavior, source, rule, description?, editability,
 *    notInEffect?}], workspaceDirectories, managedOnly}}; `claude edit-permission-rules --json` with
 *    {op: "add", rules, behavior, destination} or {op: "remove", rule, behavior, source}.
 *  - Memory: `get_memory_dialog` → {files, folders, memories?, auto_memory, auto_dream}; `claude edit-memory-settings --json`
 *    with {autoMemoryEnabled?, autoDreamEnabled?}.
 *
 * Pure: unit-tested. The page sends indexes into the last listing plus the few fields a form edits; every edit is built
 * here from the CLI's own rows, so the page can't name another file, rule or hook.
 */
object CustomizeEdits {
    /** Settings files the CLI lets these subcommands write. */
    val SOURCES = listOf("userSettings", "projectSettings", "localSettings")
    val BEHAVIORS = listOf("allow", "ask", "deny")
    private const val RULE_MAX = 10_000
    private const val COMMAND_MAX = 10_000

    fun sourceLabel(s: String?): String = when (s) {
        "userSettings" -> "All your projects (~/.claude/settings.json)"
        "projectSettings" -> "This project, shared (.claude/settings.json)"
        "localSettings" -> "This project, just you (.claude/settings.local.json)"
        "policySettings" -> "Your organization (managed)"
        "session", "cliArg" -> "This session only"
        else -> s ?: ""
    }

    // ---------- hooks ----------

    fun hooks(payload: JsonObject?): JsonObject = JsonObject().apply {
        add("events", JsonArray().apply {
            payload.objects("eventCatalog").take(60).forEach { e ->
                add(JsonObject().apply {
                    addProperty("name", (e.str("name") ?: "").take(60))
                    addProperty("summary", (e.str("summary") ?: "").take(200))
                    addProperty("supportsMatcher", e.isTrue("supportsMatcher"))
                })
            }
        })
        add("hooks", JsonArray().apply {
            payload.objects("hooks").take(300).forEach { h ->
                val editable = h.obj("editable")
                val config = editable?.obj("config")
                add(JsonObject().apply {
                    addProperty("event", (h.str("event") ?: "").take(60))
                    addProperty("matcher", (h.str("matcher") ?: "").take(300))
                    addProperty("source", (h.str("source") ?: "").take(40))
                    addProperty("sourceLabel", (h.str("sourceLabel") ?: "").take(120))
                    addProperty("type", (h.str("type") ?: "").take(40))
                    addProperty("text", (h.str("commandText") ?: h.str("displayText") ?: "").take(2000))
                    addProperty("contentLabel", (h.str("contentLabel") ?: "").take(40))
                    h.get("timeout")?.takeIf { it.isJsonPrimitive }?.let { addProperty("timeout", runCatching { it.asInt }.getOrDefault(0)) }
                    addProperty("disabled", h.isTrue("disabled"))
                    addProperty("editable", editable != null && h.str("source") in SOURCES)
                    // Only command hooks get the form; others can be removed.
                    addProperty("formEditable", editable != null && h.str("source") in SOURCES && config?.str("type") == "command")
                })
            }
        })
        val p = payload?.obj("policy")
        val locked = p != null && (p.isTrue("disabledByPolicy") || p.isTrue("managedOnly") || p.isTrue("pluginOnly") || p.isTrue("policyUnreadable"))
        addProperty("locked", locked)
        if (locked) addProperty("lockReason", "Your organization's managed settings control hooks here, so they can't be edited.")
        else if (p?.isTrue("allDisabled") == true) addProperty("notice", "disableAllHooks is on: none of these hooks run.")
    }

    /** One hook edit, or null when the request doesn't match the listing (rule 4). [index] points into the last listing. */
    fun hookEdit(
        listing: JsonObject?, op: String?, index: Int?, source: String?, event: String?, matcher: String?, command: String?, timeout: Int?,
    ): JsonObject? {
        val events = listing.objects("eventCatalog").associateBy { it.str("name") }
        val rows = listing.objects("hooks")
        fun hook(base: JsonObject?): JsonObject? {
            val cmd = command?.trim()?.takeIf { it.isNotEmpty() && it.length <= COMMAND_MAX } ?: return null
            return (base?.deepCopy() ?: JsonObject().apply { addProperty("type", "command") }).apply {
                addProperty("command", cmd)
                if (timeout != null && timeout in 1..3600) addProperty("timeout", timeout) else remove("timeout")
            }
        }
        fun matcherFor(ev: String): String? {
            val m = matcher?.trim() ?: ""
            if (m.length > 1000) return null
            return if (events[ev]?.isTrue("supportsMatcher") == true) m else ""
        }
        return when (op) {
            "add" -> {
                if (source !in SOURCES || event == null || event !in events) return null
                JsonObject().apply {
                    addProperty("op", "add")
                    addProperty("source", source)
                    addProperty("event", event)
                    addProperty("matcher", matcherFor(event) ?: return null)
                    add("hook", hook(null) ?: return null)
                }
            }
            "replace", "remove" -> {
                val row = index?.let { rows.getOrNull(it) } ?: return null
                val editable = row.obj("editable") ?: return null
                val rowSource = row.str("source")?.takeIf { it in SOURCES } ?: return null
                val target = JsonObject().apply {
                    addProperty("event", row.str("event") ?: return null)
                    addProperty("matcher", editable.str("matcher") ?: "")
                    add("hook", editable.obj("config")?.deepCopy() ?: return null)
                }
                JsonObject().apply {
                    addProperty("op", op)
                    addProperty("source", rowSource)
                    if (op == "remove") {
                        addProperty("event", target.str("event"))
                        addProperty("matcher", target.str("matcher"))
                    } else {
                        if (editable.obj("config")?.str("type") != "command") return null
                        val ev = event?.takeIf { it in events } ?: return null
                        addProperty("event", ev)
                        addProperty("matcher", matcherFor(ev) ?: return null)
                        add("hook", hook(editable.obj("config")) ?: return null)
                    }
                    add("target", target)
                }
            }
            else -> null
        }
    }

    // ---------- permissions ----------

    fun permissions(payload: JsonObject?): JsonObject {
        val state = payload?.obj("state") ?: payload
        return JsonObject().apply {
            add("rules", JsonArray().apply {
                state.objects("rules").take(500).forEach { r ->
                    add(JsonObject().apply {
                        addProperty("behavior", r.str("behavior")?.takeIf { it in BEHAVIORS } ?: "ask")
                        addProperty("rule", (r.str("rule") ?: "").take(2000))
                        addProperty("source", sourceLabel(r.str("source")))
                        r.obj("description")?.let { d ->
                            addProperty("description", listOfNotNull(d.str("prefix"), d.str("emphasis"), d.str("suffix")).joinToString("").take(500))
                        }
                        addProperty("removable", r.str("editability") == "persistent" && r.str("source") in SOURCES && !r.isTrue("notInEffect"))
                        addProperty("notInEffect", r.isTrue("notInEffect"))
                    })
                }
            })
            add("directories", JsonArray().apply {
                state.objects("workspaceDirectories").take(50).forEach { d ->
                    add(JsonObject().apply { addProperty("path", (d.str("path") ?: "").take(500)); addProperty("source", sourceLabel(d.str("source"))) })
                }
            })
            addProperty("managedOnly", state?.isTrue("managedOnly") == true)
        }
    }

    /** Rules typed one per line. Null when nothing valid or the behavior / destination isn't one the CLI accepts. */
    fun permissionAdd(text: String?, behavior: String?, destination: String?): JsonObject? {
        if (behavior !in BEHAVIORS || destination !in SOURCES) return null
        val rules = text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: return null
        if (rules.isEmpty() || rules.size > 100 || rules.any { it.length > RULE_MAX }) return null
        return JsonObject().apply {
            addProperty("op", "add")
            add("rules", JsonArray().apply { rules.forEach { add(it) } })
            addProperty("behavior", behavior)
            addProperty("destination", destination)
        }
    }

    /** Removing the rule at [index] of the last listing: only a rule saved in a settings file. */
    fun permissionRemove(listing: JsonObject?, index: Int?): JsonObject? {
        val state = listing?.obj("state") ?: listing
        val r = index?.let { state.objects("rules").getOrNull(it) } ?: return null
        if (r.str("editability") != "persistent" || r.isTrue("notInEffect")) return null
        val source = r.str("source")?.takeIf { it in SOURCES } ?: return null
        return JsonObject().apply {
            addProperty("op", "remove")
            addProperty("rule", r.str("rule") ?: return null)
            addProperty("behavior", r.str("behavior")?.takeIf { it in BEHAVIORS } ?: return null)
            addProperty("source", source)
        }
    }

    // ---------- memory ----------

    fun memory(payload: JsonObject?): JsonObject = JsonObject().apply {
        add("files", JsonArray().apply {
            payload.objects("files").take(30).forEach { f ->
                add(JsonObject().apply {
                    addProperty("label", (f.str("label") ?: "").take(120))
                    addProperty("description", (f.str("description") ?: "").take(300))
                    addProperty("path", (f.str("path") ?: "").take(500))
                    addProperty("exists", f.isTrue("exists"))
                })
            }
        })
        add("folders", JsonArray().apply {
            payload.objects("folders").take(20).forEach { f ->
                add(JsonObject().apply {
                    addProperty("label", (f.str("label") ?: "").take(120))
                    addProperty("description", (f.str("description") ?: "").take(300))
                    addProperty("path", (f.str("path") ?: "").take(500))
                })
            }
        })
        add("memories", JsonArray().apply {
            payload.objects("memories").take(200).forEach { m ->
                add(JsonObject().apply {
                    addProperty("name", (m.str("name") ?: "").take(200))
                    addProperty("description", (m.str("description") ?: "").take(300))
                    m.str("type")?.let { addProperty("kind", it.take(20)) }
                    m.get("modified_ms")?.takeIf { it.isJsonPrimitive }?.let { addProperty("modified", runCatching { it.asLong }.getOrDefault(0L)) }
                })
            }
        })
        payload?.obj("auto_memory")?.let { a ->
            add("autoMemory", JsonObject().apply {
                addProperty("enabled", a.isTrue("enabled")); addProperty("toggleable", a.isTrue("toggleable")); addProperty("status", (a.str("status") ?: "").take(120))
            })
        }
        payload?.obj("auto_dream")?.takeIf { it.isTrue("shown") }?.let { a ->
            add("autoDream", JsonObject().apply {
                addProperty("enabled", a.isTrue("enabled")); addProperty("toggleable", a.isTrue("toggleable"))
                addProperty("status", (a.str("status") ?: "").take(120)); addProperty("detail", (a.str("detail") ?: "").take(120))
            })
        }
    }

    fun memoryEdit(key: String?, on: Boolean): JsonObject? = when (key) {
        "autoMemory" -> JsonObject().apply { addProperty("autoMemoryEnabled", on) }
        "autoDream" -> JsonObject().apply { addProperty("autoDreamEnabled", on) }
        else -> null
    }

    /** A path from the last memory listing: a file row, a folder row or a saved memory, by index. */
    fun memoryPath(listing: JsonObject?, what: String?, index: Int?): Pair<String, Boolean>? {
        val list = when (what) { "file" -> "files"; "folder" -> "folders"; "memory" -> "memories"; else -> return null }
        val row = index?.let { listing.objects(list).getOrNull(it) } ?: return null
        val path = row.str("path")?.takeIf { it.isNotBlank() } ?: return null
        // Only the user and project files are listed while missing; those two may be created (the CLI's own rule).
        val creatable = what == "file" && !row.isTrue("exists") && row.str("kind") in setOf("user", "project")
        return path to creatable
    }

    /** What the CLI subcommand printed: {ok, ...} on success, else its last line as the reason. */
    fun cliResult(exit: Int, out: String): Pair<Boolean, String> {
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val json = lines.lastOrNull { it.startsWith("{") }?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        if (exit == 0 && json?.isTrue("ok") != false) {
            val warnings = (json?.get("warnings") as? JsonArray)?.mapNotNull { it.takeIf { w -> w.isJsonPrimitive }?.asString }?.joinToString(" ") ?: ""
            return true to warnings.take(500)
        }
        val reason = json?.str("error") ?: lines.lastOrNull() ?: "exit code $exit"
        return false to reason.take(500)
    }

    private fun JsonObject?.objects(key: String): List<JsonObject> = (this?.get(key) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.isTrue(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
}
