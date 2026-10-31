package dev.metaclaude.panel

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vcs.changes.ChangeListManager
import dev.metaclaude.protocol.FuzzyPaths
import dev.metaclaude.settings.ClaudeUiSettings

/**
 * Phase 9: project files and folders for `@` mentions, relative to their content root with "/" separators (folders end
 * with "/"). Excluded files are never listed; git-ignored ones neither when respectGitIgnore is on. Background threads only.
 */
class ProjectPaths(private val project: Project) {
    @Volatile private var cache: List<String> = emptyList()
    @Volatile private var builtAt = 0L

    fun search(query: String, limit: Int = 30): List<String> = FuzzyPaths.top(query, paths(), limit)

    private fun paths(): List<String> {
        if (System.currentTimeMillis() - builtAt < MAX_AGE_MS && cache.isNotEmpty()) return cache
        val ignoreGit = ClaudeUiSettings.instance.state.respectGitIgnore
        val out = ArrayList<String>()
        ApplicationManager.getApplication().runReadAction {
            val index = ProjectFileIndex.getInstance(project)
            val clm = ChangeListManager.getInstance(project)
            index.iterateContent { f: VirtualFile ->
                if (out.size >= MAX_PATHS) return@iterateContent false
                if (index.isExcluded(f) || (ignoreGit && clm.isIgnoredFile(f))) return@iterateContent true
                val root = index.getContentRootForFile(f) ?: return@iterateContent true
                if (f == root) return@iterateContent true
                val rel = VfsUtilCore.getRelativePath(f, root) ?: return@iterateContent true
                out.add(if (f.isDirectory) "$rel/" else rel)
                true
            }
        }
        cache = out
        builtAt = System.currentTimeMillis()
        return out
    }

    private companion object {
        const val MAX_AGE_MS = 30_000L
        const val MAX_PATHS = 100_000
    }
}
