package dev.metaclaude.protocol

/**
 * Phase 16a: which `claude` the panel talks to. The panel uses control requests and stream events of the CLI it was
 * built against ([TESTED]); an older CLI may not know some of them, a new major version may have changed them.
 * Pure: unit-tested.
 */
object CliVersion {
    const val TESTED = "2.1.316"
    private const val UNSUPPORTED = "Unsupported control request subtype"

    fun parse(s: String?): List<Int>? =
        Regex("""(\d+)\.(\d+)\.(\d+)""").find(s ?: return null)?.groupValues?.drop(1)?.map { it.toInt() }

    fun compare(a: List<Int>, b: List<Int>): Int =
        a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0

    /** A one-line notice for the panel, or null when the version is fine (or unknown: never guess). */
    fun notice(version: String?, tested: String = TESTED): String? {
        val v = parse(version) ?: return null
        val t = parse(tested) ?: return null
        val shown = v.joinToString(".")
        return when {
            compare(v, t) < 0 -> "Your Claude Code is $shown; Clawed UI was built and tested with $tested. Some panel features " +
                "may not work until you update: run `claude update` in a terminal, then start a new conversation."
            v[0] > t[0] -> "Claude Code $shown is a new major version; Clawed UI was tested with $tested. " +
                "If something in the panel misbehaves, check for a Clawed UI update."
            else -> null
        }
    }

    /** The CLI's answer to a control request it doesn't know, in words that say what to do. Other errors unchanged. */
    fun friendlyError(err: String): String {
        if (!err.startsWith(UNSUPPORTED)) return err
        val what = err.substringAfter(':', "").trim().takeIf { Regex("[a-z_]{1,64}").matches(it) }
        return "your Claude Code doesn't support this yet${what?.let { " ($it)" } ?: ""}: run `claude update`, then start a new conversation"
    }
}
