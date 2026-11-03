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
                file, "${Instant.now()} ${if (tag.isNullOrBlank()) "" else "[$tag] "}$msg\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        }
    }

    val path: Path get() = file
}
