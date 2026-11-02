package dev.metaclaude.protocol

/**
 * Phase 15a: the Rust extras' texts. Everything ends up in the chat's message box for you to send (nothing is sent by
 * itself). Pure: unit-tested.
 */
object RustHelp {
    data class Problem(val severity: String, val message: String, val line: Int)

    /** "Ask Claude to fix": the problems on the caret's line(s), with the file mentioned so Claude reads it. */
    fun fixPrompt(path: String, problems: List<Problem>): String {
        val shown = problems.distinct().take(10)
        val lines = shown.map { it.line }.distinct().sorted()
        val where = when {
            lines.isEmpty() -> "@$path"
            lines.size == 1 -> "@$path#${lines[0]}"
            else -> "@$path#${lines.first()}-${lines.last()}"
        }
        return buildString {
            append("Fix ").append(if (shown.size == 1) "this problem" else "these problems").append(" in ").append(where).append(":\n")
            shown.forEach { p -> append("- ").append(p.severity).append(" (line ").append(p.line).append("): ").append(p.message.lineSequence().first().take(500)).append('\n') }
        }
    }

    const val OUTPUT_MAX = 12_000

    /** "Send to Claude" from a Run / cargo console: the selection, or the end of the output, as a fenced block. */
    fun consolePrompt(title: String?, text: String): String {
        val clean = text.replace("\r\n", "\n").filter { it == '\n' || it == '\t' || it >= ' ' }.trimEnd()
        val tail = if (clean.length > OUTPUT_MAX) "…\n" + clean.takeLast(OUTPUT_MAX) else clean
        val fence = if (tail.contains("```")) "~~~" else "```"
        return "Here is the output of ${title?.takeIf { it.isNotBlank() }?.let { "\"${it.take(80)}\"" } ?: "a run"} in the IDE. " +
            "Explain what went wrong and fix it:\n\n${fence}text\n$tail\n$fence\n"
    }

    /** Ready-made requests: Claude runs cargo itself, reads the errors and fixes them. */
    val QUICK = linkedMapOf(
        "check" to "Run `cargo check --all-targets` and fix every error it reports. Re-run it until it passes.",
        "clippy" to "Run `cargo clippy --all-targets -- -D warnings` and fix the warnings (no `#[allow]` unless a fix would change behavior). Re-run it until it passes.",
        "test" to "Run `cargo test` and fix the failing tests. If a test itself is wrong, say why before changing it. Re-run until everything passes.",
    )

    /** The PATH for claude with the chosen Rust toolchain folder first (VS Code's usePythonEnvironment, for Rust). */
    fun pathWith(toolchainDir: String?, currentPath: String?, separator: String): String? {
        val dir = toolchainDir?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val rest = currentPath.orEmpty()
        return if (rest.isEmpty()) dir else "$dir$separator$rest"
    }
}
