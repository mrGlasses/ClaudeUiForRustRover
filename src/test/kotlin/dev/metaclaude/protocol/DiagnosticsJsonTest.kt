package dev.metaclaude.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsJsonTest {
    @Test
    fun `diagnostics are shaped like the VS Code extension's`() {
        val json = DiagnosticsJson.toJson(listOf(
            DiagnosticsJson.FileDiagnostics(
                "file:///C:/p/src/main.rs",
                listOf(DiagnosticsJson.Diagnostic("mismatched types", "Error", 4, 8, 4, 12, "rustc")),
            ),
        ))
        val file = JsonParser.parseString(json).asJsonArray[0].asJsonObject
        assertEquals("file:///C:/p/src/main.rs", file.get("uri").asString)
        val d = file.getAsJsonArray("diagnostics")[0].asJsonObject
        assertEquals("mismatched types", d.get("message").asString)
        assertEquals("Error", d.get("severity").asString)
        assertEquals("rustc", d.get("source").asString)
        val range = d.getAsJsonObject("range")
        assertEquals(4, range.getAsJsonObject("start").get("line").asInt)
        assertEquals(8, range.getAsJsonObject("start").get("character").asInt)
        assertEquals(12, range.getAsJsonObject("end").get("character").asInt)
    }

    @Test
    fun `no files gives an empty array`() {
        assertEquals("[]", DiagnosticsJson.toJson(emptyList()))
    }

    @Test
    fun `file URLs from the CLI become Windows paths`() {
        assertEquals("C:\\Users\\Nick\\p\\main.rs", CliPaths.fromFileUrl("file:///c%3A/Users/Nick/p/main.rs", windows = true).replaceFirst("c:", "C:"))
        assertEquals("C:\\Users\\Nick\\a b\\main.rs", CliPaths.fromFileUrl("file:///C:/Users/Nick/a%20b/main.rs", windows = true))
        assertEquals("C:\\p\\main.rs", CliPaths.fromFileUrl("C:/p/main.rs", windows = true))
        assertEquals("/home/nick/main.rs", CliPaths.fromFileUrl("file:///home/nick/main.rs", windows = false))
    }
}
