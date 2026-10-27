package dev.metaclaude.protocol

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/** JSON written to ~/.claude/ide/<port>.lock. The port is the file NAME, not a field. */
data class LockFileContent(
    val pid: Long,
    val workspaceFolders: List<String>,
    val ideName: String,
    val transport: String = "ws",
    val runningInWindows: Boolean = false,
    val authToken: String,
)

object ClaudeDirs {
    /** Honors CLAUDE_CONFIG_DIR, like the official extensions do. */
    fun ideDir(): Path {
        val base = System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".claude")
        return base.resolve("ide")
    }
}

object AuthToken {
    /** 128-bit token as 32 lowercase hex chars, from the OS CSPRNG. */
    fun generate(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

class LockFile(private val port: Int, private val content: LockFileContent) : AutoCloseable {
    private val gson = Gson()
    val path: Path = ClaudeDirs.ideDir().resolve("$port.lock")

    fun write() {
        val dir = path.parent
        Files.createDirectories(dir)
        runCatching { Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------")) }
        // temp file + atomic move so the CLI never reads a half-written file
        val tmp = Files.createTempFile(dir, "$port-", ".tmp")
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
        Files.writeString(tmp, gson.toJson(content))
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun close() {
        Files.deleteIfExists(path)
    }

    companion object {
        /** Remove OUR dead lock files only (matching ideName); never touch other IDEs' files. */
        fun sweepStale(ideName: String) {
            val dir = ClaudeDirs.ideDir()
            if (!Files.isDirectory(dir)) return
            Files.list(dir).use { files ->
                files.filter { it.fileName.toString().endsWith(".lock") }.forEach { p ->
                    runCatching {
                        val c = Gson().fromJson(Files.readString(p), LockFileContent::class.java)
                        if (c.ideName == ideName && !ProcessHandle.of(c.pid).isPresent) Files.deleteIfExists(p)
                    }
                }
            }
        }
    }
}

/** Finds lock files written by OTHER IDE plugins (e.g. the official one) that cover a project. */
object LockScanner {
    data class Found(val port: Int, val content: LockFileContent)

    fun forWorkspace(projectRoot: Path, excludePort: Int? = null): List<Found> {
        val dir = ClaudeDirs.ideDir()
        if (!Files.isDirectory(dir)) return emptyList()
        val root = projectRoot.toRealPath()
        return Files.list(dir).use { files ->
            files.filter { it.fileName.toString().endsWith(".lock") }.toList().mapNotNull { p ->
                runCatching {
                    val port = p.fileName.toString().removeSuffix(".lock").toInt()
                    if (port == excludePort) return@runCatching null
                    val c = Gson().fromJson(Files.readString(p), LockFileContent::class.java)
                    val alive = ProcessHandle.of(c.pid).isPresent
                    // path-segment prefix match: /a/proj must not match /a/proj-other
                    val covers = c.workspaceFolders.any { root.startsWith(Path.of(it)) }
                    if (alive && covers) Found(port, c) else null
                }.getOrNull()
            }
        }
    }
}
