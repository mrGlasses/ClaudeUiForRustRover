// Bundles the chat panel into dist/panel.js + dist/panel.css. The Kotlin side inlines both into one HTML page
// with a per-load CSP nonce (see ChatPanel.kt), so the bundle must be fully self-contained: no imports at runtime,
// no remote URLs, no eval.
import { build } from "esbuild";
import { mkdir, copyFile } from "node:fs/promises";

await mkdir("dist", { recursive: true });
await build({
  entryPoints: ["src/main.ts"],
  bundle: true,
  format: "iife",
  target: "chrome120",
  minify: true,
  sourcemap: false,
  legalComments: "eof", // keeps the MIT/Apache notices of bundled libraries
  outfile: "dist/panel.js",
});
await copyFile("src/panel.css", "dist/panel.css");
console.log("webview built: dist/panel.js, dist/panel.css");
