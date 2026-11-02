package dev.metaclaude.protocol

/** Phase 14c: the `jetbrains://.../clawed-ui/<target>?...` links, checked before anything happens. Pure: unit-tested. */
object UriCommands {
    data class Request(val kind: String, val prompt: String? = null, val session: String? = null, val plugin: String? = null)

    private val NAME = Regex("^[A-Za-z0-9_.-]{1,100}$")
    const val PROMPT_MAX = 10_000

    fun parse(target: String?, params: Map<String, String>): Request? = when (target?.trim('/')) {
        "open" -> {
            val prompt = params["prompt"]?.filter { it == '\n' || it == '\t' || it >= ' ' }?.take(PROMPT_MAX)?.takeIf { it.isNotBlank() }
            val session = params["session"]?.takeIf { SessionFiles.isSessionId(it) }
            Request("open", prompt = prompt, session = session)
        }
        "install-plugin" -> {
            val plugin = params["plugin"]?.takeIf { NAME.matches(it) } ?: return null
            val market = params["marketplace"]?.takeIf { NAME.matches(it) }
            Request("install-plugin", plugin = if (market != null) "$plugin@$market" else plugin)
        }
        else -> null
    }
}
