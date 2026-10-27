package dev.metaclaude.tools

import com.google.gson.JsonObject
import dev.metaclaude.protocol.Mcp
import java.util.concurrent.CompletableFuture

/** Replies an openDiff call can end with. */
object DiffReply {
    const val SAVED = "FILE_SAVED"
    const val REJECTED = "DIFF_REJECTED"
    const val TAB_CLOSED = "TAB_CLOSED"

    /** Line separators as IntelliJ documents require them (\n only). */
    fun toDocument(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * The text sent back with FILE_SAVED. Unchanged proposal → the CLI's exact bytes, so it doesn't think you edited it.
     * Edited → your text, in the proposal's line-separator style.
     */
    fun finalText(proposal: String, editedDocText: String): String = when {
        editedDocText == toDocument(proposal) -> proposal
        proposal.contains("\r\n") -> editedDocText.replace("\n", "\r\n")
        else -> editedDocText
    }

    fun saved(proposal: String, editedDocText: String): JsonObject = Mcp.texts(SAVED, finalText(proposal, editedDocText))
    fun rejected(): JsonObject = Mcp.text(REJECTED)
    fun tabClosed(): JsonObject = Mcp.text(TAB_CLOSED)
}

/**
 * Open openDiff calls, keyed by tab_name. Each one is answered exactly once (CLAUDE.md rule):
 * a later resolve for the same tab is a no-op, and re-opening a tab answers the old call first.
 * Futures are completed outside the lock because completing one sends the WebSocket reply.
 */
class PendingDiffs<R> {
    private val map = LinkedHashMap<String, CompletableFuture<R>>()

    /** Registers a new call. A still-pending call with the same tab gets [replaced] first. */
    fun open(tab: String, replaced: R): CompletableFuture<R> {
        val fresh = CompletableFuture<R>()
        val old = synchronized(this) { map.put(tab, fresh) }
        old?.complete(replaced)
        return fresh
    }

    /** Answers the call for [tab]. Returns false if nothing was pending (already answered). */
    fun resolve(tab: String, result: R): Boolean {
        val f = synchronized(this) { map.remove(tab) } ?: return false
        return f.complete(result)
    }

    fun fail(tab: String, error: Throwable): Boolean {
        val f = synchronized(this) { map.remove(tab) } ?: return false
        return f.completeExceptionally(error)
    }

    /** Answers every pending call (project close, plugin unload). Returns how many were pending. */
    fun resolveAll(result: R): Int {
        val all = synchronized(this) { map.values.toList().also { map.clear() } }
        all.forEach { it.complete(result) }
        return all.size
    }

    @Synchronized fun isPending(tab: String): Boolean = map.containsKey(tab)
}
