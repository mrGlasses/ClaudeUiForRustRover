package dev.metaclaude.protocol

/**
 * Phase 10c: how the history list is organized. Your renames, archive marks and groups are the plugin's own notes about
 * Claude Code's sessions (kept in the project's workspace file; the session files themselves are never written).
 * Pure: unit-tested.
 */
object SessionOrganizer {
    const val NAME_MAX = 100

    enum class Filter { ACTIVE, OPEN, ARCHIVED, ALL }

    /** The plugin's notes. [unarchived]: sessions you brought back, so auto-archive leaves them alone. */
    data class Notes(
        val titles: Map<String, String> = emptyMap(),
        val archived: Set<String> = emptySet(),
        val unarchived: Set<String> = emptySet(),
        val groupOf: Map<String, String> = emptyMap(),
        val groups: List<String> = emptyList(),
    )

    data class Item(
        val id: String,
        val title: String,
        val modified: Long,
        val prompts: Int,
        val archived: Boolean,
        val autoArchived: Boolean,
        val group: String?,
        val open: Boolean,
    )

    /** Archived by you, or (with [autoArchiveDays] > 0) idle longer than that and not brought back by you. */
    fun isArchived(s: SessionFiles.Summary, notes: Notes, now: Long, autoArchiveDays: Int): Pair<Boolean, Boolean> {
        if (s.id in notes.archived) return true to false
        if (autoArchiveDays <= 0 || s.id in notes.unarchived) return false to false
        val idle = now - s.modified > autoArchiveDays * 86_400_000L
        return idle to idle
    }

    /** Items for the panel: your titles applied, filtered, groups first (in your order), then the rest, newest first. */
    fun view(
        sessions: List<SessionFiles.Summary>,
        notes: Notes,
        openIds: Set<String>,
        filter: Filter,
        now: Long,
        autoArchiveDays: Int,
    ): List<Item> {
        val items = sessions.map { s ->
            val (archived, auto) = isArchived(s, notes, now, autoArchiveDays)
            Item(
                id = s.id,
                title = notes.titles[s.id]?.takeIf { it.isNotBlank() } ?: s.title,
                modified = s.modified,
                prompts = s.prompts,
                archived = archived,
                autoArchived = auto,
                group = notes.groupOf[s.id]?.takeIf { it in notes.groups },
                open = s.id in openIds,
            )
        }.filter {
            when (filter) {
                Filter.ACTIVE -> !it.archived || it.open
                Filter.OPEN -> it.open
                Filter.ARCHIVED -> it.archived
                Filter.ALL -> true
            }
        }
        val order = notes.groups.withIndex().associate { it.value to it.index }
        return items.sortedWith(compareBy<Item>({ it.group?.let { g -> order[g] } ?: Int.MAX_VALUE }).thenByDescending { it.modified })
    }

    /** A title or group name as typed: one line, trimmed, bounded. Null when empty. */
    fun cleanName(s: String?): String? = s?.lineSequence()?.firstOrNull()?.trim()?.take(NAME_MAX)?.takeIf { it.isNotEmpty() }
}
