// Phase 6.6 preview: proves the pipeline (build in CI → bundled → loaded in JCEF under CSP) and the security rules.
// The real chat UI replaces this in Phase 7.
import { onMessage, post } from "./bridge";
import { renderMarkdown } from "./markdown";

const SAMPLE = [
  "## Claude UI panel preview",
  "",
  "If you can read this, the web panel **built in CI**, was **bundled** into the plugin and **loaded** in RustRover.",
  "",
  "Security checks (nothing below should run or navigate):",
  "",
  "- Raw HTML in Markdown is shown as text: <img src=x onerror=\"document.title='PWNED'\"> <script>alert(1)</script>",
  "- A `javascript:` link is removed: [click me](javascript:alert(1))",
  "- A normal link opens in your system browser, not here: [anthropic.com](https://www.anthropic.com)",
  "",
  "```rust",
  "fn main() { println!(\"code blocks render\"); }",
  "```",
].join("\n");

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text; // textContent, never innerHTML, for anything not from renderMarkdown
  return e;
}

const root = document.getElementById("app")!;
const status = el("div", "status", "Waiting for the plugin…");
const body = el("div", "markdown");
body.innerHTML = renderMarkdown(SAMPLE); // sanitized
const pingButton = el("button", "ping", "Ping the plugin");
pingButton.addEventListener("click", () => post({ type: "ping" }));
root.append(status, body, pingButton);

// Every link click goes to Kotlin (panel rule 3); the panel itself never navigates.
document.addEventListener("click", (ev) => {
  const a = (ev.target as Element | null)?.closest("a");
  if (!a) return;
  ev.preventDefault();
  const href = a.getAttribute("href") ?? "";
  if (/^https?:/i.test(href)) post({ type: "openLink", url: href });
});

onMessage((msg) => {
  switch (msg.type) {
    case "hello":
      status.textContent = `Connected to Claude UI ${msg.version} (IDE ${msg.ideBuild}).`;
      break;
    case "pong":
      status.textContent = `Plugin answered at ${msg.at}.`;
      break;
    case "error":
      status.textContent = `Error: ${msg.message}`;
      break;
  }
});

post({ type: "ready" });
