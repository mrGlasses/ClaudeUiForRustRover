package dev.metaclaude.panel

import dev.metaclaude.protocol.BridgeLog
import dev.metaclaude.protocol.SessionFiles
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

/**
 * Phase 10: this project's sessions, read from Claude Code's own files (`~/.claude/projects/<project dir>/<id>.jsonl`).
 * Read-only. Background threads only. The folder name is derived from the project path; if Claude Code named it
 * differently, the folder whose sessions ran in this project (their `cwd`) is used instead.
 */
class SessionHistory(private val projectRoot: Path, private val logTag: String) {
    private val projectsDir: Path = Path.of(System.getProperty("user.home"), ".claude", "projects")
    private val maxFileBytes = 64L * 1024 * 1024

    fun list(): List<SessionFiles.Summary> {
        val dir = folder() ?: return emptyList()
        if (cache.size > CACHE_MAX) cache.clear()
        return files(dir).mapNotNull { f ->
            runCatching {
                // Phase 16c: a session file is read again only when it changed (size or time); before, every open of the
                // history read every session in full.
                val size = Files.size(f)
                val modified = Files.getLastModifiedTime(f).toMillis()
                val key = f.toString()
                cache[key]?.takeIf { it.size == size && it.modified == modified }?.let { return@runCatching it.summary }
                val summary = Files.newBufferedReader(f, StandardCharsets.UTF_8).useLines { lines ->
                    SessionFiles.summarize(f.nameWithoutExtension, lines, modified)
                }
                cache[key] = Cached(size, modified, summary)
                summary
            }.getOrNull()
        }.sortedByDescending { it.modified }
    }

    /** Sessions whose title or any line contains [query] (case-insensitive), newest first. */
    fun search(query: String): List<SessionFiles.Summary> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list()
        val dir = folder() ?: return emptyList()
        return list().filter { s ->
            s.title.lowercase().contains(q) || runCatching {
                Files.newBufferedReader(dir.resolve("${s.id}.jsonl"), StandardCharsets.UTF_8).useLines { l -> l.any { it.contains(q, ignoreCase = true) } }
            }.getOrDefault(false)
        }
    }

    /** Runs [block] over the lines of session [id]; null when there's no such session here. */
    fun <T> read(id: String, block: (Sequence<String>) -> T): T? {
        if (!SessionFiles.isSessionId(id)) return null
        val f = (folder() ?: return null).resolve("$id.jsonl")
        if (!f.isRegularFile() || Files.size(f) > maxFileBytes) return null
        return Files.newBufferedReader(f, StandardCharsets.UTF_8).useLines(block)
    }

    fun exists(id: String) = SessionFiles.isSessionId(id) && folder()?.resolve("$id.jsonl")?.isRegularFile() == true

    private fun files(dir: Path): List<Path> = Files.list(dir).use { s ->
        s.filter { it.isRegularFile() && it.extension == "jsonl" && Files.size(it) <= maxFileBytes }.toList()
    }

    private fun folder(): Path? {
        if (!projectsDir.isDirectory()) return null
        val direct = projectsDir.resolve(SessionFiles.projectDirName(projectRoot.toString()))
        if (direct.isDirectory()) return direct
        // Fallback: the folder whose newest session ran here (Windows path case differs between tools).
        val root = projectRoot.toString()
        val found = Files.list(projectsDir).use { dirs ->
            dirs.filter { it.isDirectory() }.toList().firstOrNull { d ->
                runCatching {
                    files(d).maxByOrNull { Files.getLastModifiedTime(it).toMillis() }?.let { f ->
                        Files.newBufferedReader(f, StandardCharsets.UTF_8).useLines { l ->
                            l.take(50).any { it.contains("\"cwd\"") && it.lowercase().contains(root.lowercase().replace("\\", "\\\\")) }
                        }
                    } ?: false
                }.getOrDefault(false)
            }
        }
        if (found == null) BridgeLog.log("history: no session folder for this project in ~/.claude/projects", logTag)
        return found
    }

    private data class Cached(val size: Long, val modified: Long, val summary: SessionFiles.Summary?)

    private companion object {
        /** Summaries by session file, shared by all tabs and windows. */
        val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
        const val CACHE_MAX = 5000
    }
}
