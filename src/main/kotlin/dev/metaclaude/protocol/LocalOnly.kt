package dev.metaclaude.protocol

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.util.EnumSet

/**
 * Phase 16b (security review): checks shared by the two local servers, and owner-only files on Windows. Pure parts unit-tested.
 */
object LocalOnly {
    private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")

    /**
     * A request may come only from a local program, never from a web page: no `Origin` header (browsers always send one
     * on WebSocket and cross-site requests; the CLI never does), and a `Host` naming loopback and our own port (a web page
     * that re-points its own domain to 127.0.0.1, "DNS rebinding", sends its domain here). The token is still required:
     * this is a second lock, not the only one.
     */
    fun requestAllowed(host: String?, origin: String?, port: Int): Boolean {
        if (!origin.isNullOrEmpty()) return false
        if (host.isNullOrEmpty()) return true // HTTP/1.0 style: nothing to check, the token decides
        val h = host.trim().lowercase()
        val name = if (h.startsWith("[")) h.substringBefore("]") + "]" else h.substringBefore(':')
        val rest = h.removePrefix(name)
        val portOk = rest.isEmpty() || rest == ":$port"
        return name in LOCAL_HOSTS && portOk
    }

    /** True when [owner] (as Windows prints it, `DOMAIN\name`) is the user running the IDE. */
    fun isUser(owner: String, user: String): Boolean =
        owner.equals(user, ignoreCase = true) || owner.substringAfterLast('\\').equals(user, ignoreCase = true)

    /**
     * Windows: leaves only the file's owner in its access list (files under the user profile otherwise also inherit
     * SYSTEM and Administrators, and a CLAUDE_CONFIG_DIR elsewhere may add Users or Everyone). Skipped when the owner
     * isn't you (IDE run as administrator): then the change could lock you out. POSIX systems use permissions instead.
     * Returns what happened, for the log.
     */
    fun restrictToOwner(path: Path): String {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return "posix"
        return runCatching {
            val owner = view.owner
            if (!isUser(owner.name, System.getProperty("user.name") ?: "")) return "skipped (owner is not you)"
            val before = view.acl
            view.acl = listOf(
                AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission::class.java)).build(),
            )
            if (!Files.isReadable(path) || !Files.isWritable(path)) { view.acl = before; "restored (lost access)" } else "owner only"
        }.getOrElse { "failed (${it::class.java.simpleName})" }
    }
}
