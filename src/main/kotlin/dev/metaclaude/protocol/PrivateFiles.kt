package dev.metaclaude.protocol

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 16a: the chat sessions' small files (MCP config with the panel server's token, extra settings), in
 * ~/.claude-meta/tmp instead of the system temp folder. Each name carries the IDE's pid, so files left by a crashed
 * IDE are found and removed at the next start without touching another running IDE's files.
 * Owner-only: POSIX permissions, or on Windows an access list with only you (Phase 16b, [LocalOnly.restrictToOwner]).
 */
object PrivateFiles {
    val dir: Path get() = Path.of(System.getProperty("user.home"), ".claude-meta", "tmp")
    private val NAME = Regex("""[a-z]+-(\d+)-[^/\\]*\.json""")
    private val swept = AtomicBoolean(false)

    fun create(kind: String, content: String, dir: Path = this.dir, pid: Long = ProcessHandle.current().pid()): Path {
        require(Regex("[a-z]{1,20}").matches(kind))
        Files.createDirectories(dir)
        runCatching { Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------")) }
        val f = Files.createTempFile(dir, "$kind-$pid-", ".json")
        runCatching { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")) }
        val acl = LocalOnly.restrictToOwner(f) // Windows (Phase 16b); before the token is written
        if (acl != "posix" && acl != "owner only") BridgeLog.log("session file access list: $acl")
        Files.writeString(f, content)
        return f
    }

    /** Deletes our files whose IDE process is gone. Returns how many. */
    fun sweep(dir: Path = this.dir, alive: (Long) -> Boolean = { ProcessHandle.of(it).isPresent }): Int {
        if (!Files.isDirectory(dir)) return 0
        return Files.list(dir).use { files ->
            files.toList().count { f ->
                val pid = NAME.matchEntire(f.fileName.toString())?.groupValues?.get(1)?.toLongOrNull() ?: return@count false
                !alive(pid) && runCatching { Files.deleteIfExists(f) }.getOrDefault(false)
            }
        }
    }

    /** Files from builds before 0.39.0 (system temp folder, no pid in the name): removed once they're a day old. */
    fun sweepLegacy(tmp: Path = Path.of(System.getProperty("java.io.tmpdir")), now: Instant = Instant.now()): Int {
        if (!Files.isDirectory(tmp)) return 0
        return Files.list(tmp).use { files ->
            files.toList().count { f ->
                val n = f.fileName.toString()
                (n.startsWith("claude-ui-mcp") || n.startsWith("clawed-ui-settings")) && n.endsWith(".json") &&
                    runCatching { Duration.between(Files.getLastModifiedTime(f).toInstant(), now) > Duration.ofDays(1) && Files.deleteIfExists(f) }.getOrDefault(false)
            }
        }
    }

    /** Once per IDE run, before the first chat session. */
    fun sweepOnce() {
        if (!swept.compareAndSet(false, true)) return
        val n = runCatching { sweep() }.getOrDefault(0) + runCatching { sweepLegacy() }.getOrDefault(0)
        if (n > 0) BridgeLog.log("removed $n leftover session file(s) from earlier IDE runs")
    }
}
