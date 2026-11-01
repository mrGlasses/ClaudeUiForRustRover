package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Phase 11a: checkpoints. Every prompt the panel sends carries its own UUID (the CLI keeps it as the session entry's `uuid`),
 * and the CLI runs with `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true`, so each prompt is a restore point:
 *
 *  - rewind code:  control request `{"subtype":"rewind_files","user_message_id":<uuid>,"dry_run":<bool>}`, answered with
 *                  `{canRewind, error?, filesChanged?, insertions?, deletions?}`. Only Write/Edit/NotebookEdit changes are
 *                  tracked (not Bash, not subagents).
 *  - fork:         a new process with `--resume <session> --fork-session --resume-session-at <entry before the prompt>`,
 *                  so the new branch keeps everything up to (not including) that prompt. Forking at the first prompt is
 *                  just a new conversation.
 *
 * Shapes come from the CLI's own schema (claude 2.1.316). Pure: unit-tested.
 */
object Checkpoints {
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    const val ENV = "CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING"
    val ACTIONS = setOf("fork", "rewind", "forkRewind")

    fun isUuid(s: String?) = s != null && UUID.matches(s)

    /** A prompt you typed in a session file: its text and the chain entry just before it (null for the first prompt). */
    data class Prompt(val uuid: String, val parentUuid: String?, val text: String)

    /** The prompt with [uuid] in a session file, or null if it isn't one of your prompts there. */
    fun prompt(lines: Sequence<String>, uuid: String): Prompt? {
        for (line in lines) {
            if (!line.contains(uuid)) continue // cheap filter: most lines are other entries
            val o = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
            if (o.str("type") != "user" || o.str("uuid") != uuid) continue
            if (o.isTrue("isMeta") || o.isTrue("isSidechain")) return null
            val text = SessionFiles.promptText((o.get("message") as? JsonObject)?.get("content")) ?: return null
            return Prompt(uuid, o.str("parentUuid")?.takeIf { isUuid(it) }, text)
        }
        return null
    }

    fun rewindRequest(uuid: String, dryRun: Boolean) = JsonObject().apply {
        addProperty("user_message_id", uuid)
        addProperty("dry_run", dryRun)
    }

    data class RewindResult(val canRewind: Boolean, val error: String?, val files: List<String>, val insertions: Int, val deletions: Int)

    /** The CLI's answer to `rewind_files`: a success payload, or an error string (an older CLI, a bad id). */
    fun result(payload: JsonObject?, error: String?): RewindResult {
        if (payload == null) return RewindResult(false, (error ?: "no answer from Claude").take(300), emptyList(), 0, 0)
        val files = (payload.get("filesChanged") as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }?.take(500) ?: emptyList()
        return RewindResult(
            canRewind = payload.get("canRewind")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
            error = payload.str("error")?.take(300),
            files = files,
            insertions = payload.int("insertions"),
            deletions = payload.int("deletions"),
        )
    }

    /** The native confirmation's text: what will change, and what rewinding can't undo. */
    fun confirmText(r: RewindResult, prompt: String, fork: Boolean): String = buildString {
        append("Restore the files Claude changed after this message?\n\n\"").append(short(prompt, 120)).append("\"\n\n")
        val shown = r.files.take(15)
        append("${r.files.size} file${if (r.files.size == 1) "" else "s"} will change (+${r.insertions} −${r.deletions}):\n")
        shown.forEach { append("  ").append(it).append('\n') }
        if (r.files.size > shown.size) append("  … and ${r.files.size - shown.size} more\n")
        append("\nChanges made with Bash commands or by subagents aren't undone.")
        if (fork) append("\nThe conversation then continues in a new tab from before this message.")
        else append("\nThe conversation itself stays as it is.")
    }

    /** CLI arguments for the forked process; at the first prompt there is nothing to keep, so no resume. */
    fun forkArgs(sessionId: String, resumeAt: String?): List<String> =
        if (resumeAt == null) emptyList() else listOf("--resume", sessionId, "--fork-session", "--resume-session-at", resumeAt)

    fun short(s: String, max: Int): String = s.lineSequence().first().trim().let { if (it.length > max) it.take(max) + "…" else it }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(key: String): Int = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0
    private fun JsonObject.isTrue(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
}
