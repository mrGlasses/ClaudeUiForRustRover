package dev.metaclaude.cli

import dev.metaclaude.protocol.CliPaths
import java.io.File
import java.util.concurrent.TimeUnit

/** Finds the user's own `claude` and checks that it runs. */
object ClaudeCli {
    fun candidates(windows: Boolean): List<String> =
        if (windows) listOf("claude.exe", "claude.cmd", "claude.bat", "claude") else listOf("claude")

    /**
     * Configured path if set (must exist), else the first claude on PATH, else ~/.local/bin (native installer).
     * Pure: the file system is passed in as [isFile], so it is unit-testable.
     */
    fun resolve(configured: String?, pathEnv: String?, home: String, windows: Boolean, isFile: (String) -> Boolean): String? {
        if (!configured.isNullOrBlank()) return configured.trim().takeIf(isFile)
        val sep = if (windows) ';' else ':'
        val slash = if (windows) "\\" else "/"
        val dirs = (pathEnv ?: "").split(sep).map { it.trim().trim('"') }.filter { it.isNotEmpty() } +
            listOf("$home$slash.local${slash}bin")
        for (dir in dirs) for (name in candidates(windows)) {
            val p = dir.trimEnd('\\', '/') + slash + name
            if (isFile(p)) return p
        }
        return null
    }

    fun resolve(configured: String?): String? =
        resolve(configured, System.getenv("PATH"), System.getProperty("user.home"), CliPaths.isWindows) { File(it).isFile }

    /** True when [exe] sits in a PATH folder, so typing `claude` in a shell finds it. */
    fun isOnPath(exe: String): Boolean {
        val dir = File(exe).parentFile?.canonicalPath ?: return false
        val sep = if (CliPaths.isWindows) ';' else ':'
        return (System.getenv("PATH") ?: "").split(sep).map { it.trim().trim('"') }.filter { it.isNotEmpty() }
            .any { runCatching { File(it).canonicalPath.equals(dir, ignoreCase = CliPaths.isWindows) }.getOrDefault(false) }
    }

    data class Check(val ok: Boolean, val message: String)

    /** Runs `claude --version`. Blocking (up to 15 s): call from a background thread. */
    fun check(configured: String?): Check {
        val exe = resolve(configured) ?: return Check(
            false,
            if (configured.isNullOrBlank()) "claude not found on PATH or in ~/.local/bin" else "File not found: $configured",
        )
        return try {
            val p = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return Check(false, "$exe did not answer --version within 15 s")
            }
            val out = p.inputStream.bufferedReader().readText().trim().lineSequence().firstOrNull().orEmpty()
            if (p.exitValue() == 0) Check(true, "$out ($exe)") else Check(false, "Exit code ${p.exitValue()}: $out")
        } catch (e: Exception) {
            Check(false, "Could not run $exe: ${e.message}")
        }
    }
}
