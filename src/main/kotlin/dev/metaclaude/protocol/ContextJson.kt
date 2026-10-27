package dev.metaclaude.protocol

import com.google.gson.JsonObject

/** Paths in the form the CLI uses on this OS. IntelliJ uses forward slashes everywhere, the Windows CLI doesn't. */
object CliPaths {
    val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

    /** "C:/Users/a/b.rs" → "C:\Users\a\b.rs" on Windows; unchanged elsewhere. */
    fun forCli(path: String, windows: Boolean = isWindows): String =
        if (windows) path.replace('/', '\\') else path

    /** file URL like VS Code sends: file:///C:/Users/a/b.rs (spaces encoded). */
    fun fileUrl(path: String): String {
        val p = path.replace('\\', '/').replace(" ", "%20")
        return if (p.startsWith("/")) "file://$p" else "file:///$p"
    }
}

/** IDE → CLI notification payloads (shapes from claudecode.nvim PROTOCOL.md). Lines and characters are 0-based. */
object ContextJson {
    private fun pos(line: Int, character: Int) = JsonObject().apply {
        addProperty("line", line); addProperty("character", character)
    }

    fun selection(
        text: String, filePath: String,
        startLine: Int, startChar: Int, endLine: Int, endChar: Int,
    ): JsonObject = JsonObject().apply {
        addProperty("text", text)
        addProperty("filePath", filePath)
        addProperty("fileUrl", CliPaths.fileUrl(filePath))
        add("selection", JsonObject().apply {
            add("start", pos(startLine, startChar))
            add("end", pos(endLine, endChar))
            addProperty("isEmpty", startLine == endLine && startChar == endChar)
        })
    }

    fun atMention(filePath: String, lineStart: Int, lineEnd: Int): JsonObject = JsonObject().apply {
        addProperty("filePath", filePath)
        addProperty("lineStart", lineStart)
        addProperty("lineEnd", lineEnd)
    }
}
