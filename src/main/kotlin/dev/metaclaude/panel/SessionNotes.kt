package dev.metaclaude.panel

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import dev.metaclaude.protocol.SessionOrganizer

/**
 * Phase 10c: your renames, archive marks and groups for this project's conversations. Kept in the project's workspace file
 * (.idea/workspace.xml, not shared through git). Claude Code's session files are never written.
 */
@Service(Service.Level.PROJECT)
@State(name = "ClawedUiSessionNotes", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SessionNotes : SimplePersistentStateComponent<SessionNotes.Options>(Options()) {
    class Options : BaseState() {
        var titles by map<String, String>()
        var archived by stringSet()
        var unarchived by stringSet()
        var groupOf by map<String, String>()
        var groups by list<String>()
        /** Phase 14b: bookmarked responses, each a small JSON object (see [Bookmarks]), newest last. */
        var bookmarks by list<String>()
    }

    @Synchronized fun bookmarks(): List<String> = ArrayList(state.bookmarks)

    @Synchronized fun addBookmark(json: String) {
        state.bookmarks = ArrayList(state.bookmarks).apply { add(json); while (size > dev.metaclaude.protocol.Bookmarks.MAX) removeAt(0) }
    }

    @Synchronized fun removeBookmark(id: String) {
        state.bookmarks = ArrayList(state.bookmarks).apply { removeIf { dev.metaclaude.protocol.Bookmarks.idOf(it) == id } }
    }

    @Synchronized
    fun notes() = SessionOrganizer.Notes(
        titles = HashMap(state.titles),
        archived = HashSet(state.archived),
        unarchived = HashSet(state.unarchived),
        groupOf = HashMap(state.groupOf),
        groups = ArrayList(state.groups),
    )

    // Each change assigns a new collection, so the platform sees it and saves it (no internal API needed).

    @Synchronized fun rename(id: String, title: String?) {
        state.titles = HashMap(state.titles).apply { if (title == null) remove(id) else put(id, title) }
    }

    @Synchronized fun setArchived(id: String, archived: Boolean) {
        state.archived = HashSet(state.archived).apply { if (archived) add(id) else remove(id) }
        state.unarchived = HashSet(state.unarchived).apply { if (archived) remove(id) else add(id) }
    }

    @Synchronized fun setGroup(id: String, group: String?) {
        state.groupOf = HashMap(state.groupOf).apply { if (group == null || group !in state.groups) remove(id) else put(id, group) }
    }

    @Synchronized fun createGroup(name: String) {
        if (name !in state.groups) state.groups = ArrayList(state.groups).apply { add(name) }
    }

    @Synchronized fun renameGroup(old: String, new: String) {
        val i = state.groups.indexOf(old)
        if (i < 0 || new in state.groups) return
        state.groups = ArrayList(state.groups).apply { set(i, new) }
        state.groupOf = HashMap(state.groupOf).apply { replaceAll { _, g -> if (g == old) new else g } }
    }

    /** Deleting a group keeps its conversations; they just leave the group. */
    @Synchronized fun deleteGroup(name: String) {
        state.groups = ArrayList(state.groups).apply { remove(name) }
        state.groupOf = HashMap(state.groupOf).apply { values.removeIf { it == name } }
    }
}
