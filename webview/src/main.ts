// Phase 7a chat panel: send prompts, stream Claude's answer as sanitized Markdown, show tool calls, stop a turn.
// Text from Claude goes through renderMarkdown (sanitized); everything else uses textContent.
import { onMessage, post, type FromKotlin } from "./bridge";
import { renderMarkdown } from "./markdown";

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

// ---------- layout ----------
const root = document.getElementById("app")!;
const header = el("div", "header");
const status = el("span", "status", "Starting…");
const newButton = el("button", "link-button", "New session");
header.append(status, newButton);

const transcript = el("div", "transcript");
const empty = el("div", "empty", "Ask Claude about this project. Enter sends, Shift+Enter adds a line, Esc stops.");
transcript.append(empty);

const composer = el("div", "composer");
const input = el("textarea", "input");
input.rows = 3;
input.placeholder = "Message Claude…";
const sendButton = el("button", "send", "Send");
composer.append(input, sendButton);
root.append(header, transcript, composer);

// ---------- state ----------
let busy = false;
let model = "";
const textBlocks = new Map<string, { el: HTMLDivElement; text: string }>();
// Streamed text blocks per message, in order, not yet replaced by their final text. The CLI sends the final text of
// each block as its own one-block message, so finals are matched in order, not by index (v0.14.0 duplicated text).
const unfinished = new Map<string, string[]>();
const toolCards = new Map<string, HTMLDivElement>();
let renderQueued = new Set<string>();

function setBusy(b: boolean): void {
  busy = b;
  sendButton.textContent = b ? "Stop" : "Send";
  sendButton.classList.toggle("stop", b);
  status.textContent = b ? `Working${model ? ` · ${model}` : ""}…` : model ? `Ready · ${model}` : "Ready";
}

function nearBottom(): boolean {
  return transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight < 60;
}

function append(node: HTMLElement, forceScroll = false): void {
  const stick = forceScroll || nearBottom();
  empty.remove();
  transcript.append(node);
  if (stick) transcript.scrollTop = transcript.scrollHeight;
}

/** Markdown is re-rendered at most once per frame per block while it streams. */
function renderBlock(key: string): void {
  if (renderQueued.has(key)) return;
  renderQueued.add(key);
  requestAnimationFrame(() => {
    renderQueued.delete(key);
    const b = textBlocks.get(key);
    if (!b) return;
    const stick = nearBottom();
    b.el.innerHTML = renderMarkdown(b.text); // sanitized
    if (stick) transcript.scrollTop = transcript.scrollHeight;
  });
}

function textBlock(key: string): { el: HTMLDivElement; text: string } {
  let b = textBlocks.get(key);
  if (!b) {
    b = { el: el("div", "msg assistant markdown"), text: "" };
    textBlocks.set(key, b);
    append(b.el);
  }
  return b;
}

// ---------- sending ----------
function sendPrompt(): void {
  const text = input.value.trim();
  if (!text || busy) return;
  append(el("div", "msg user", text), true);
  input.value = "";
  setBusy(true);
  post({ type: "send", text });
}

sendButton.addEventListener("click", () => (busy ? post({ type: "interrupt" }) : sendPrompt()));
input.addEventListener("keydown", (ev) => {
  if (ev.key === "Enter" && !ev.shiftKey && !ev.isComposing) {
    ev.preventDefault();
    sendPrompt();
  }
});
document.addEventListener("keydown", (ev) => {
  if (ev.key === "Escape" && busy) {
    ev.preventDefault();
    post({ type: "interrupt" });
  }
});
newButton.addEventListener("click", () => post({ type: "newSession" }));

// Every link click goes to Kotlin (panel rule 3); the panel itself never navigates.
document.addEventListener("click", (ev) => {
  const a = (ev.target as Element | null)?.closest("a");
  if (!a) return;
  ev.preventDefault();
  const href = a.getAttribute("href") ?? "";
  if (/^https?:/i.test(href)) post({ type: "openLink", url: href });
});

// ---------- receiving ----------
function handle(msg: FromKotlin): void {
  switch (msg.type) {
    case "hello":
      status.textContent = "Ready";
      break;
    case "sessionInfo":
      model = msg.model;
      setBusy(busy);
      break;
    case "busy":
      setBusy(true);
      break;
    case "textDelta": {
      if (!textBlocks.has(msg.key)) {
        const list = unfinished.get(msg.messageId) ?? [];
        list.push(msg.key);
        unfinished.set(msg.messageId, list);
      }
      const b = textBlock(msg.key);
      b.text += msg.text;
      renderBlock(msg.key);
      break;
    }
    case "textFinal": {
      const streamed = unfinished.get(msg.messageId)?.shift();
      const b = textBlock(streamed ?? msg.key);
      b.text = msg.text;
      renderBlock(msg.key);
      break;
    }
    case "toolUse": {
      const card = el("div", "tool");
      card.append(el("span", "tool-name", msg.name), el("span", "tool-summary", msg.summary), el("span", "tool-state", "…"));
      toolCards.set(msg.id, card);
      append(card);
      break;
    }
    case "toolResult": {
      const card = toolCards.get(msg.toolUseId);
      if (!card) break;
      card.classList.add(msg.isError ? "error" : "done");
      card.querySelector(".tool-state")!.textContent = msg.isError ? "✗" : "✓";
      if (msg.preview) {
        const details = el("details", "tool-output");
        details.append(el("summary", undefined, msg.isError ? "Error" : "Output"), el("pre", undefined, msg.preview));
        card.append(details);
      }
      break;
    }
    case "notice":
      append(el("div", "notice", msg.text));
      break;
    case "turnEnd": {
      const parts: string[] = [];
      if (msg.durationMs) parts.push(`${(msg.durationMs / 1000).toFixed(1)} s`);
      if (msg.costUsd) parts.push(`$${msg.costUsd.toFixed(4)}`);
      if (msg.isError) parts.push("ended with an error");
      if (parts.length) append(el("div", "meta", parts.join(" · ")));
      setBusy(false);
      break;
    }
    case "sessionReset":
      textBlocks.clear();
      unfinished.clear();
      toolCards.clear();
      transcript.replaceChildren(empty);
      model = "";
      setBusy(false);
      input.focus();
      break;
    case "error":
      append(el("div", "notice error", msg.message));
      setBusy(false);
      break;
  }
}

onMessage(handle);
post({ type: "ready" });
input.focus();
