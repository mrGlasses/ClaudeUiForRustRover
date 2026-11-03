package dev.metaclaude.protocol

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Plain-text debug log: %USERPROFILE%\.claude-meta\bridge.log (or ~/.claude-meta/bridge.log).
 * Never write the auth token or message bodies here (bodies can contain source code).
 */
object BridgeLog {
    private val file: Path = Path.of(System.getProperty("user.home"), ".claude-meta", "bridge.log")

    /** [tag] is the project name, so several RustRover windows can share one log. */
    @Synchronized
    fun log(msg: String, tag: String? = null) {
        runCatching {
            Files.createDirectories(file.parent)
            // Size cap: the previous part is kept as bridge.log.1 (Phase 16a; before, the whole log was deleted).
            if (Files.exists(file) && Files.size(file) > 1_000_000) Files.move(file, file.resolveSibling("bridge.log.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            Files.writeString(
                file, "${Instant.now()} ${if (tag.isNullOrBlank()) "" else "[$tag] "}${clean(msg)}\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        }
    }

    val path: Path get() = file

    private val TOKEN = Regex("(?<![0-9a-fA-F])[0-9a-f]{32}(?![0-9a-fA-F])")

    /**
     * Phase 16b safety net: one line each, at most 1000 characters, and anything shaped like our auth tokens
     * (32 hex digits) replaced, in case a future log line quotes one by mistake.
     */
    fun clean(msg: String): String =
        TOKEN.replace(msg.replace('\r', ' ').replace('\n', ' '), "[redacted]").let { if (it.length > 1000) it.take(1000) + "…" else it }
}
