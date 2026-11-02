package dev.metaclaude.protocol

/**
 * Phase 14c: how the plugin starts `claude` (VS Code's `environmentVariables` and `claudeProcessWrapper`): extra environment
 * variables, e.g. CLAUDE_CODE_USE_BEDROCK=1 and AWS_REGION for a third-party provider, and an optional wrapper program that
 * receives the claude path and its arguments. Values may hold secrets: never log them. Pure: unit-tested.
 */
object ProcessOptions {
    private val KEY = Regex("^[A-Za-z_][A-Za-z0-9_]{0,200}$")

    /** One KEY=value per line; blank lines and # comments skipped; invalid names reported, not used. */
    fun parseEnv(text: String?): Pair<Map<String, String>, List<String>> {
        val env = LinkedHashMap<String, String>()
        val bad = ArrayList<String>()
        text?.lines()?.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val i = line.indexOf('=')
            val key = if (i > 0) line.substring(0, i).trim() else ""
            if (!KEY.matches(key)) { bad += line.substringBefore('=').take(40); return@forEach }
            env[key] = line.substring(i + 1).trim().removeSurrounding("\"")
        }
        return env to bad
    }

    /** The command line: [wrapper] claude args… */
    fun command(wrapper: String?, exe: String, args: List<String>): List<String> =
        listOfNotNull(wrapper?.trim()?.takeIf { it.isNotEmpty() }, exe) + args

    /** Names only, for the log line ("2 extra variables: AWS_REGION, CLAUDE_CODE_USE_BEDROCK"). */
    fun describe(env: Map<String, String>): String = if (env.isEmpty()) "none" else "${env.size}: ${env.keys.sorted().joinToString(", ")}"
}
