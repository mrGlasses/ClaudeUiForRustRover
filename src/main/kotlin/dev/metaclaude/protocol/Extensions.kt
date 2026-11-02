package dev.metaclaude.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Phase 13c: the MCP and Plugins tabs of the Customize view (claude 2.1.316).
 *
 *  - MCP: `mcp_status` → {mcpServers: [{name, status, error?, config?, scope?, source?, transport?, urlHost?, tools?}]};
 *    `mcp_toggle {serverName, enabled}`, `mcp_reconnect {serverName}` on the running session; adding and removing go through
 *    the public `claude mcp add [-t http|sse] [-e K=V] [-H "K: V"] -s <scope> <name> <command|url> [-- args]` and
 *    `claude mcp remove <name> -s <scope>` (they write ~/.claude.json or .mcp.json, which new sessions read).
 *  - Plugins: the public `claude plugin list --json --available` → {installed: [{id, version, scope, enabled,
 *    hasUserConfig}], available: [{pluginId, name, description, marketplaceName, version?}]}; `plugin enable|disable|
 *    uninstall <id> -s <scope> --json`, `plugin install <id> -s <scope> --json`, `plugin configure <id> --json` (schema,
 *    inputs) and `--values-stdin --json` (a JSON object of single-line strings); `plugin marketplace list --json`,
 *    `marketplace add <source>`, `marketplace remove <name>`, `marketplace update [name]`. Then `reload_plugins`.
 *
 * Pure: unit-tested. Every argument list is built here from validated values: on Windows `claude.cmd` runs through cmd.exe,
 * which mangles quotes and expands % and ^, so free text with those characters is refused there ([cmdSafe]).
 */
object Extensions {
    val SCOPES = listOf("local", "project", "user")
    val TRANSPORTS = listOf("stdio", "http", "sse")
    private val NAME = Regex("^[A-Za-z0-9_.-]{1,100}$")
    private val PLUGIN_ID = Regex("^[A-Za-z0-9_.-]{1,100}(@[A-Za-z0-9_.-]{1,100})?$")
    private val ENV_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]{0,100}$")
    /** The panel's own IDE-tools server: shown, never edited from here. */
    const val OWN_SERVER = "clawed-ui"

    /** Free text safe to pass as one argument when the CLI is a .cmd/.bat (cmd.exe in between). */
    fun cmdSafe(s: String, viaCmd: Boolean): Boolean =
        s.length <= 4000 && s.none { it == '\n' || it == '\r' || it == '\u0000' } && (!viaCmd || s.none { it in "\"%^!" })

    fun viaCmd(exe: String) = exe.lowercase().let { it.endsWith(".cmd") || it.endsWith(".bat") }

    // ---------- MCP ----------

    fun mcpServers(payload: JsonObject?): JsonArray = JsonArray().also { out ->
        (payload?.get("mcpServers") as? JsonArray)?.take(100)?.forEach { el ->
            val s = el as? JsonObject ?: return@forEach
            val name = s.str("name")?.take(100) ?: return@forEach
            val config = s.obj("config")
            val scope = s.str("scope") ?: s.str("source")
            out.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("status", (s.str("status") ?: "").take(20))
                s.str("error")?.let { addProperty("error", it.take(300)) }
                addProperty("scope", (scope ?: "").take(20))
                addProperty("transport", (s.str("transport") ?: config?.str("type") ?: "").take(20))
                val target = config?.str("url") ?: s.str("urlHost") ?: config?.str("command")?.let { c ->
                    (listOf(c) + ((config.get("args") as? JsonArray)?.mapNotNull { it.takeIf { a -> a.isJsonPrimitive }?.asString } ?: emptyList())).joinToString(" ")
                }
                target?.let { addProperty("target", it.take(300)) }
                addProperty("tools", (s.get("tools") as? JsonArray)?.size() ?: 0)
                addProperty("own", name == OWN_SERVER)
                addProperty("removable", name != OWN_SERVER && scope in SCOPES)
            })
        }
    }

    /** `claude mcp add` arguments for the form, or an error message. */
    fun mcpAddArgs(
        name: String?, transport: String?, scope: String?, target: String?, args: String?, pairs: String?, viaCmd: Boolean,
    ): Result<List<String>> {
        val n = name?.trim() ?: ""
        if (!NAME.matches(n) || n == OWN_SERVER) return Result.failure(IllegalArgumentException("Name: letters, digits, '-', '_' or '.' only."))
        if (transport !in TRANSPORTS) return Result.failure(IllegalArgumentException("Pick stdio, http or sse."))
        if (scope !in SCOPES) return Result.failure(IllegalArgumentException("Pick where to save it."))
        val t = target?.trim() ?: ""
        if (t.isEmpty()) return Result.failure(IllegalArgumentException(if (transport == "stdio") "Type the command to run." else "Type the server's URL."))
        if (transport != "stdio" && !(t.startsWith("https://") || t.startsWith("http://"))) return Result.failure(IllegalArgumentException("The URL must start with https:// or http://."))
        val extra = args?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val kv = pairs?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val out = mutableListOf("mcp", "add", "-s", scope!!)
        if (transport != "stdio") out += listOf("-t", transport!!)
        for (p in kv) {
            if (transport == "stdio") {
                val key = p.substringBefore('=', "")
                if (!ENV_KEY.matches(key)) return Result.failure(IllegalArgumentException("Environment lines look like KEY=value."))
                out += listOf("-e", p)
            } else {
                if (!p.contains(':')) return Result.failure(IllegalArgumentException("Header lines look like Name: value."))
                out += listOf("-H", p)
            }
        }
        out += listOf(n, t)
        if (transport == "stdio" && extra.isNotEmpty()) out += listOf("--") + extra
        if (out.any { !cmdSafe(it, viaCmd) }) return Result.failure(IllegalArgumentException(
            "Quotes, %, ^ or ! can't be passed safely through claude.cmd: add this server with `claude mcp add` in a terminal."))
        return Result.success(out)
    }

    fun mcpRemoveArgs(row: JsonObject?): List<String>? {
        val name = row?.str("name")?.takeIf { NAME.matches(it) && it != OWN_SERVER } ?: return null
        val scope = (row.str("scope") ?: row.str("source"))?.takeIf { it in SCOPES } ?: return null
        return listOf("mcp", "remove", name, "-s", scope)
    }

    fun mcpRow(payload: JsonObject?, name: String?): JsonObject? =
        (payload?.get("mcpServers") as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("name") == name }

    // ---------- plugins ----------

    fun plugins(listing: JsonElement?, marketplaces: JsonElement?): JsonObject = JsonObject().apply {
        val obj = listing as? JsonObject
        val installed = (obj?.get("installed") ?: listing) as? JsonArray
        add("installed", JsonArray().apply {
            installed?.take(200)?.forEach { el ->
                val p = el as? JsonObject ?: return@forEach
                val id = p.str("id")?.takeIf { PLUGIN_ID.matches(it) } ?: return@forEach
                add(JsonObject().apply {
                    addProperty("id", id)
                    addProperty("version", (p.str("version") ?: "").take(40))
                    addProperty("scope", (p.str("scope") ?: "").take(20))
                    addProperty("enabled", p.isTrue("enabled"))
                    addProperty("hasUserConfig", p.isTrue("hasUserConfig"))
                })
            }
        })
        add("available", JsonArray().apply {
            (obj?.get("available") as? JsonArray)?.take(500)?.forEach { el ->
                val p = el as? JsonObject ?: return@forEach
                val id = p.str("pluginId")?.takeIf { PLUGIN_ID.matches(it) } ?: return@forEach
                add(JsonObject().apply {
                    addProperty("id", id)
                    addProperty("name", (p.str("name") ?: id).take(100))
                    addProperty("description", (p.str("description") ?: "").take(300))
                    addProperty("marketplace", (p.str("marketplaceName") ?: "").take(100))
                    p.str("version")?.let { addProperty("version", it.take(40)) }
                })
            }
        })
        add("marketplaces", JsonArray().apply {
            (marketplaces as? JsonArray)?.take(50)?.forEach { el ->
                val m = el as? JsonObject ?: return@forEach
                val name = m.str("name")?.takeIf { NAME.matches(it) } ?: return@forEach
                add(JsonObject().apply {
                    addProperty("name", name)
                    addProperty("source", listOfNotNull(m.str("source"), m.str("repo") ?: m.str("url") ?: m.str("path")).joinToString(": ").take(300))
                })
            }
        })
    }

    fun pluginArgs(op: String?, id: String?, scope: String?): List<String>? {
        if (id == null || !PLUGIN_ID.matches(id)) return null
        return when (op) {
            "enable", "disable", "uninstall", "install" -> {
                if (scope !in SCOPES) return null
                listOf("plugin", op!!, id, "-s", scope!!, "--json")
            }
            "configure" -> listOf("plugin", "configure", id, "--json")
            "saveConfig" -> listOf("plugin", "configure", id, "--values-stdin", "--json")
            else -> null
        }
    }

    fun marketplaceArgs(op: String?, value: String?, viaCmd: Boolean): List<String>? = when (op) {
        "add" -> value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 500 && cmdSafe(it, viaCmd) && !it.startsWith("-") }
            ?.let { listOf("plugin", "marketplace", "add", it) }
        "remove" -> value?.takeIf { NAME.matches(it) }?.let { listOf("plugin", "marketplace", "remove", it) }
        "update" -> if (value.isNullOrEmpty()) listOf("plugin", "marketplace", "update")
            else value.takeIf { NAME.matches(it) }?.let { listOf("plugin", "marketplace", "update", it) }
        else -> null
    }

    /** `plugin configure --json` as a form: each option's type, title, description, current value, choices, secret or not. */
    fun configForm(payload: JsonObject?): JsonObject = JsonObject().apply {
        val schema = payload?.obj("schema") ?: JsonObject()
        val inputs = payload?.obj("inputs") ?: JsonObject()
        val choices = payload?.obj("choices") ?: JsonObject()
        addProperty("id", payload?.str("pluginId") ?: "")
        addProperty("title", (payload?.str("displayName") ?: payload?.str("pluginId") ?: "").take(100))
        add("fields", JsonArray().apply {
            schema.entrySet().take(50).forEach { (key, v) ->
                val f = v as? JsonObject ?: return@forEach
                if (!NAME.matches(key)) return@forEach
                add(JsonObject().apply {
                    addProperty("key", key)
                    addProperty("type", (f.str("type") ?: "string").take(20))
                    addProperty("title", (f.str("title") ?: key).take(100))
                    addProperty("description", (f.str("description") ?: "").take(300))
                    addProperty("secret", f.isTrue("sensitive") || f.isTrue("secret"))
                    val current = inputs.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                    addProperty("value", if (f.isTrue("sensitive") || f.isTrue("secret")) "" else current.take(1000))
                    (choices.get(key) as? JsonArray)?.let { c ->
                        add("choices", JsonArray().apply { c.take(50).forEach { o -> o.takeIf { it.isJsonPrimitive }?.asString?.let { add(it.take(100)) } } })
                    }
                })
            }
        })
    }

    /** The values to save: only keys of the schema, single-line strings; empty secret fields are left out (keep the stored value). */
    fun configValues(payload: JsonObject?, values: JsonObject?): JsonObject? {
        val schema = payload?.obj("schema") ?: return null
        val out = JsonObject()
        values?.entrySet()?.forEach { (k, v) ->
            val f = schema.obj(k) ?: return@forEach
            val s = v.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            if (s.contains('\n') || s.length > 2000) return@forEach
            if ((f.isTrue("sensitive") || f.isTrue("secret")) && s.isEmpty()) return@forEach
            out.addProperty(k, s)
        }
        return out.takeIf { it.size() > 0 }
    }

    /** A CLI `--json` result line: (ok, message). */
    fun jsonResult(exit: Int, out: String): Pair<Boolean, String> {
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val json = lines.lastOrNull { it.startsWith("{") }?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        val ok = exit == 0 && (json?.str("outcome")?.let { it == "ok" } ?: true)
        val msg = json?.str("message") ?: lines.lastOrNull { !it.startsWith("{") } ?: if (ok) "" else "exit code $exit"
        return ok to msg.take(500)
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.isTrue(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
}
