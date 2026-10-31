package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Phase 9: what the chat panel sends with a prompt, as Claude Code's own IDE integration words it: the open file and the
 * selection become text blocks before the prompt (`<ide_opened_file>`, `<ide_selection>`). Pure: unit-tested.
 */
object PanelContext {
    const val SELECTION_MAX = 50_000

    /** A selection: 1-based inclusive lines; [text] null when the text must not be sent (settings, ignored file). */
    data class Selection(val path: String, val startLine: Int, val endLine: Int, val text: String?)

    fun openedFile(path: String) =
        "<ide_opened_file>The user opened the file $path in the IDE. This may or may not be related to the current task.</ide_opened_file>"

    fun selection(s: Selection): String {
        val lines = if (s.startLine == s.endLine) "line ${s.startLine}" else "lines ${s.startLine} to ${s.endLine}"
        val body = s.text?.let { t -> ":\n" + (if (t.length > SELECTION_MAX) t.take(SELECTION_MAX) + "\n[selection truncated]" else t) } ?: " (text not shared)"
        return "<ide_selection>The user selected the $lines from ${s.path}$body\n\nThis may or may not be related to the current task.</ide_selection>"
    }

    /** `message.content` for a user turn: context blocks first, then the prompt. */
    fun content(prompt: String, openFile: String?, selection: Selection?): JsonArray = JsonArray().apply {
        openFile?.let { add(block(openedFile(it))) }
        selection?.let { add(block(selection(it))) }
        add(block(prompt))
    }

    private fun block(text: String) = JsonObject().apply { addProperty("type", "text"); addProperty("text", text) }
}

/** `@` mentions: fuzzy match of a query against project-relative paths ("/" separators, folders end with "/"). */
object FuzzyPaths {
    /**
     * Best [limit] matches. Every query character must appear in order (case-insensitive). Higher score for: a match in the
     * file name, consecutive characters, characters at word starts (after / . _ - or a case change), and shorter paths.
     */
    fun top(query: String, paths: List<String>, limit: Int = 30): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return paths.asSequence().filter { !it.contains('/') || it.count { c -> c == '/' } == 1 && it.endsWith("/") }
            .sortedWith(compareBy({ !it.endsWith("/") }, { it.lowercase() })).take(limit).toList()
        return paths.asSequence().mapNotNull { p -> score(q, p)?.let { p to it } }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length }.thenBy { it.first })
            .take(limit).map { it.first }.toList()
    }

    fun score(q: String, path: String): Int? {
        val lower = path.lowercase()
        val nameStart = path.trimEnd('/').lastIndexOf('/') + 1
        var i = 0
        var score = 0
        var prev = -2
        for (ch in q) {
            val found = lower.indexOf(ch, i)
            if (found < 0) return null
            score += 1
            if (found == prev + 1) score += 5
            if (found >= nameStart) score += 2
            if (found == 0 || path[found - 1] in "/._- " || (path[found].isUpperCase() && path[found - 1].isLowerCase())) score += 4
            prev = found
            i = found + 1
        }
        if (lower.trimEnd('/').substring(nameStart).startsWith(q)) score += 15
        if (lower.contains(q)) score += 10
        return score * 100 - path.length
    }
}
