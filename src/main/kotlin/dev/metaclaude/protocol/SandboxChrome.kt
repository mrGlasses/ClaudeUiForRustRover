package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Phase 13d: the Sandbox and Chrome tabs of the Customize view (claude 2.1.316), as the VS Code extension does them:
 *
 *  - Sandbox: `get_sandbox_dialog` → {supported, unsupported_reason?, locked, overrides_locked, enabled, enabled_in_settings,
 *    unavailable_reason?, mode: auto-allow|regular|disabled, auto_allow_available, no_sandbox_allowed, unsandboxed_fallback,
 *    dependencies{errors, warnings}, excluded_commands, restrictions{...}}; written with `claude edit-sandbox-settings --json`
 *    and one of {mode}, {allowUnsandboxedCommands}, {excludeCommand}.
 *  - Chrome: `get_chrome_dialog` → {allowed, subscriber, wsl, installed, connected, paired_browser?, enabled_by_default,
 *    urls{install, reconnect, permissions}}; written with `claude edit-chrome-settings --json` and {enabledByDefault}.
 *
 * Pure: unit-tested.
 */
object SandboxChrome {
    val MODES = listOf("auto-allow", "regular", "disabled")

    fun sandbox(p: JsonObject?): JsonObject = JsonObject().apply {
        if (p == null) return@apply
        addProperty("supported", p.isTrue("supported"))
        p.str("unsupported_reason")?.let {
            addProperty("unsupportedReason", when (it) {
                "wsl1" -> "Sandboxing needs WSL 2."
                "excluded_platform" -> "Your organization's settings leave this platform out."
                else -> "Sandboxing isn't available on this platform."
            })
        }
        addProperty("locked", p.isTrue("locked"))
        addProperty("overridesLocked", p.isTrue("overrides_locked"))
        addProperty("enabled", p.isTrue("enabled"))
        addProperty("enabledInSettings", p.isTrue("enabled_in_settings"))
        p.str("unavailable_reason")?.let { addProperty("unavailableReason", it.take(300)) }
        addProperty("mode", p.str("mode")?.takeIf { it in MODES } ?: "disabled")
        addProperty("autoAllowAvailable", p.isTrue("auto_allow_available"))
        addProperty("noSandboxAllowed", p.get("no_sandbox_allowed")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true)
        addProperty("unsandboxedFallback", p.isTrue("unsandboxed_fallback"))
        add("errors", strings(p.obj("dependencies")?.get("errors")))
        add("warnings", strings(p.obj("dependencies")?.get("warnings")))
        add("excluded", strings(p.get("excluded_commands")))
        val r = p.obj("restrictions")
        add("restrictions", JsonArray().apply {
            listOf(
                "fs_deny_read" to "Can't read", "fs_allow_read" to "Can read again", "fs_allow_write" to "Can also write",
                "fs_deny_write" to "Can't write", "network_allowed_domains" to "Network: allowed", "network_denied_domains" to "Network: denied",
                "unix_sockets" to "Unix sockets",
            ).forEach { (key, label) ->
                val items = strings(r?.get(key))
                if (items.size() > 0) add(JsonObject().apply { addProperty("label", label); add("items", items) })
            }
        })
    }

    private fun strings(e: com.google.gson.JsonElement?): JsonArray = JsonArray().apply {
        (e as? JsonArray)?.take(100)?.forEach { v -> v.takeIf { it.isJsonPrimitive }?.asString?.let { add(it.take(300)) } }
    }

    /** One sandbox edit: a mode the dialog offers, the fallback switch, or one command pattern to exclude. */
    fun sandboxEdit(dialog: JsonObject?, what: String?, value: String?, on: Boolean?): JsonObject? {
        if (dialog == null || !dialog.isTrue("supported")) return null
        return when (what) {
            "mode" -> {
                if (dialog.isTrue("locked") || value !in MODES) return null
                if (value == "auto-allow" && !dialog.isTrue("auto_allow_available")) return null
                if (value == "disabled" && dialog.get("no_sandbox_allowed")?.asBoolean == false) return null
                JsonObject().apply { addProperty("mode", value) }
            }
            "fallback" -> if (dialog.isTrue("overrides_locked") || on == null) null else JsonObject().apply { addProperty("allowUnsandboxedCommands", on) }
            "exclude" -> value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 1000 && !it.contains('\n') }?.let { JsonObject().apply { addProperty("excludeCommand", it) } }
            else -> null
        }
    }

    fun chrome(p: JsonObject?): JsonObject = JsonObject().apply {
        if (p == null) return@apply
        addProperty("allowed", p.isTrue("allowed"))
        addProperty("subscriber", p.isTrue("subscriber"))
        addProperty("wsl", p.isTrue("wsl"))
        addProperty("installed", p.isTrue("installed"))
        addProperty("connected", p.isTrue("connected"))
        p.str("paired_browser")?.let { addProperty("browser", it.take(100)) }
        addProperty("enabledByDefault", p.isTrue("enabled_by_default"))
        val urls = p.obj("urls")
        listOf("install", "reconnect", "permissions").forEach { k -> if (urls?.str(k)?.let { isWebUrl(it) } == true) addProperty("has_$k", true) }
    }

    /** The page behind one of the dialog's links, only an http(s) URL the CLI gave (opened by Kotlin, never by the page). */
    fun chromeUrl(p: JsonObject?, which: String?): String? =
        which?.takeIf { it in setOf("install", "reconnect", "permissions") }?.let { p?.obj("urls")?.str(it) }?.takeIf { isWebUrl(it) }

    fun chromeEdit(on: Boolean) = JsonObject().apply { addProperty("enabledByDefault", on) }

    private fun isWebUrl(u: String) = u.startsWith("https://") || u.startsWith("http://")
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.isTrue(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
}
