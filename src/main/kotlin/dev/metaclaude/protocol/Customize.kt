package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Phase 13a: the Customize view's first tabs, from the CLI (claude 2.1.316), the way the VS Code extension does it:
 *
 *  - Commands: the `initialize` answer's `commands` [{name, description, argumentHint, aliases?, builtin?}], replaced by
 *    `system/commands_changed` pushes.
 *  - Skills: `get_skills_dialog` → {skills: [{name, display_name, description, source, tokens, state, locked_by?, handles}]};
 *    a state is changed with the CLI subcommand `claude edit-skill-overrides --json` (one JSON edit on stdin:
 *    {name, state, handles?}; it writes `skillOverrides` in .claude/settings.local.json), then `reload_skills`.
 *    Both are what the VS Code extension uses; the CLI marks them as meant for it, so they may change with the CLI.
 *  - Output style: `initialize` → `output_style`, `available_output_styles`; changed with
 *    `update_settings {source: "localSettings", settings: {outputStyle}}` (live), refreshed by `reload_output_styles`.
 *  - Instructions: the CLAUDE.md files Claude Code reads, opened (or created) in the editor.
 *
 * Pure: unit-tested. The panel gets bounded plain text; it sends back only names and indexes that Kotlin checks.
 */
object Customize {
    val SKILL_STATES = listOf("on", "name-only", "user-invocable-only", "off")

    fun commands(arr: JsonArray?): JsonArray = JsonArray().also { out ->
        arr?.take(500)?.forEach { el ->
            val c = el as? JsonObject ?: return@forEach
            val name = c.str("name")?.trim()?.removePrefix("/")?.takeIf { it.isNotEmpty() && it.length <= 100 } ?: return@forEach
            out.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("description", (c.str("description") ?: "").take(300))
                addProperty("argumentHint", (c.str("argumentHint") ?: "").take(100))
                addProperty("builtin", c.isTrue("builtin"))
                add("aliases", JsonArray().apply {
                    (c.get("aliases") as? JsonArray)?.take(10)?.mapNotNull { it.takeIf { a -> a.isJsonPrimitive }?.asString?.take(60) }?.forEach { add(it) }
                })
            })
        }
    }

    fun skills(payload: JsonObject?): JsonArray = JsonArray().also { out ->
        (payload?.get("skills") as? JsonArray)?.take(500)?.forEach { el ->
            val s = el as? JsonObject ?: return@forEach
            val name = s.str("name")?.take(500) ?: return@forEach
            out.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("displayName", (s.str("display_name") ?: name).take(120))
                addProperty("description", (s.str("description") ?: "").take(300))
                addProperty("source", (s.str("source") ?: "").take(40))
                addProperty("tokens", s.get("tokens")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: 0L)
                addProperty("state", s.str("state")?.takeIf { it in SKILL_STATES } ?: "on")
                s.str("locked_by")?.let { addProperty("lockedBy", it.take(40)) }
            })
        }
    }

    /**
     * The JSON edit for `claude edit-skill-overrides`, only for a skill the CLI listed and a known state, never for a locked
     * one. The `handles` come from the CLI's own row, as the extension sends them.
     */
    fun skillEdit(payload: JsonObject?, name: String?, state: String?): JsonObject? {
        if (name == null || state !in SKILL_STATES) return null
        val row = (payload?.get("skills") as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("name") == name } ?: return null
        if (row.str("locked_by") != null) return null
        return JsonObject().apply {
            addProperty("name", name)
            addProperty("state", state)
            (row.get("handles") as? JsonObject)?.let { add("handles", it.deepCopy()) }
        }
    }

    fun outputStyleRequest(style: String?, available: List<String>): JsonObject? =
        style?.takeIf { it in available }?.let {
            JsonObject().apply {
                addProperty("source", "localSettings")
                add("settings", JsonObject().apply { addProperty("outputStyle", it) })
            }
        }

    fun styleNames(arr: JsonArray?): List<String> =
        arr?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.take(100) }?.take(100) ?: emptyList()

    /** The instruction files Claude Code reads for this project, in the order the view lists them. */
    data class Instructions(val label: String, val path: String, val hint: String)

    fun instructionFiles(home: String, root: String, sep: String): List<Instructions> {
        val r = root.trimEnd('/', '\\')
        val h = home.trimEnd('/', '\\')
        return listOf(
            Instructions("Project (shared)", "$r${sep}CLAUDE.md", "Checked in: for everyone working on this project"),
            Instructions("Project (shared, in .claude)", "$r$sep.claude${sep}CLAUDE.md", "Same as above, kept in the .claude folder"),
            Instructions("Project (just you)", "$r${sep}CLAUDE.local.md", "Your own notes for this project; add it to .gitignore"),
            Instructions("All your projects", "$h$sep.claude${sep}CLAUDE.md", "Your instructions for every project on this computer"),
        )
    }

    const val NEW_FILE_TEXT = "# Instructions for Claude\n\n"

    /** "Build a custom style": a first message to edit, so Claude writes the style file where Claude Code looks for it. */
    const val CUSTOM_STYLE_PROMPT = "Create a custom output style for me as a Markdown file in .claude/output-styles/ (front matter with " +
        "name and description, then the instructions). The style should: "

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.isTrue(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
}
