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

    /** A pasted image, already checked by [image]. */
    class Image(val mediaType: String, val base64: String)

    private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    const val IMAGE_MAX_BASE64 = 7_000_000 // about 5 MB of image
    const val IMAGES_MAX = 5
    const val TERMINAL_MAX = 30_000
    private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

    /** Rule 4: only the image types the API takes, real base64, bounded size. Null when anything is off. */
    fun image(mediaType: String?, base64: String?): Image? {
        if (mediaType == null || mediaType !in IMAGE_TYPES || base64.isNullOrEmpty() || base64.length > IMAGE_MAX_BASE64) return null
        if (base64.length % 4 != 0 || !BASE64.matches(base64)) return null
        return Image(mediaType, base64)
    }

    /** `@terminal:name` tokens in a prompt (names have no spaces: the panel writes spaces as "-"). */
    fun terminalMentions(prompt: String): List<String> =
        Regex("""(?:^|\s)@terminal:(\S+)""").findAll(prompt).map { it.groupValues[1].trimEnd('.', ',', ';', ':', '?', '!') }.distinct().toList()

    /** How a terminal tab's name is written in a mention. */
    fun terminalToken(tabName: String) = tabName.trim().replace(Regex("\\s+"), "-")

    /** The end of a terminal's output (the most recent part matters), as a context block. */
    fun terminal(name: String, output: String): String {
        val tail = if (output.length > TERMINAL_MAX) "[earlier output cut]\n" + output.takeLast(TERMINAL_MAX) else output
        return "<terminal_output name=\"$name\">\nOutput of the IDE terminal tab \"$name\":\n${tail.trimEnd()}\n</terminal_output>"
    }

    /** `message.content` for a user turn: context blocks and images first, then the prompt. */
    fun content(
        prompt: String,
        openFile: String?,
        selection: Selection?,
        terminals: List<Pair<String, String>> = emptyList(),
        images: List<Image> = emptyList(),
    ): JsonArray = JsonArray().apply {
        openFile?.let { add(block(openedFile(it))) }
        selection?.let { add(block(selection(it))) }
        terminals.forEach { (name, out) -> add(block(terminal(name, out))) }
        images.forEach { img ->
            add(JsonObject().apply {
                addProperty("type", "image")
                add("source", JsonObject().apply {
                    addProperty("type", "base64")
                    addProperty("media_type", img.mediaType)
                    addProperty("data", img.base64)
                })
            })
        }
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
