// Chat panel. 7a: send prompts, stream Claude's answer as sanitized Markdown, tool calls, stop a turn.
// 7b: thinking blocks (Ctrl+O), to-do list, queued messages, timestamps, copy a response.
// Text from Claude goes through renderMarkdown (sanitized); everything else uses textContent.
import { onMessage, post, type FromKotlin } from "./bridge";
import { renderMarkdown } from "./markdown";

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function clock(d = new Date()): string {
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

// ---------- layout ----------
const root = document.getElementById("app")!;
const header = el("div", "header");
const status = el("span", "status", "Starting…");
const newButton = el("button", "link-button", "New session");
header.append(status, newButton);

const transcript = el("div", "transcript");
const empty = el("div", "empty", "Ask Claude about this project. Enter sends, Shift+Enter adds a line, Esc stops, Ctrl+O shows thinking.");
transcript.append(empty);

// To-do list (TodoWrite): one list, replaced on every update, shown above the composer.
const todoBox = el("details", "todos");
todoBox.open = true;
// The list is Claude's (like VS Code's): you can fold it or hide it, not tick items. Hidden comes back on the next update.
const todoSummary = el("summary");
const todoLabel = el("span", "todo-label");
const todoHide = el("button", "link-button todo-hide", "Hide");
todoHide.title = "Hide until Claude updates the list";
todoHide.addEventListener("click", (ev) => {
  ev.preventDefault(); // don't also fold/unfold
  ev.stopPropagation();
  todoBox.hidden = true;
});
todoSummary.append(todoLabel, todoHide);
const todoList = el("ul", "todo-list");
todoBox.append(todoSummary, todoList);
todoBox.hidden = true;

// Messages typed while Claude works wait here and go out one by one after each turn.
const queueBox = el("div", "queue");
queueBox.hidden = true;

const composer = el("div", "composer");
const input = el("textarea", "input");
input.rows = 3;
input.placeholder = "Message Claude…";
const sendButton = el("button", "send", "Send");
composer.append(input, sendButton);
root.append(header, transcript, todoBox, queueBox, composer);

// ---------- state ----------
let busy = false;
let model = "";
const textBlocks = new Map<string, { el: HTMLDivElement; text: string }>();
// Streamed text blocks per message, in order, not yet replaced by their final text. The CLI sends the final text of
// each block as its own one-block message, so finals are matched in order, not by index (v0.14.0 duplicated text).
const unfinished = new Map<string, string[]>();
const toolCards = new Map<string, HTMLDivElement>();
const renderQueued = new Set<string>();
type Thinking = { box: HTMLDetailsElement; label: HTMLElement; body: HTMLPreElement; text: string; started: number; done: boolean };
const thinking = new Map<string, Thinking>();
let showThinking = false; // Ctrl+O
let turnTexts: string[] = []; // keys of this turn's text blocks, for Copy
const queue: string[] = [];

function setBusy(b: boolean): void {
  busy = b;
  const typing = input.value.trim() !== "";
  sendButton.textContent = b ? (typing ? "Queue" : "Stop") : "Send";
  sendButton.classList.toggle("stop", b && !typing);
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
    turnTexts.push(key);
    append(b.el);
  }
  return b;
}

// ---------- thinking ----------
function thinkingBlock(key: string): Thinking {
  let t = thinking.get(key);
  if (!t) {
    const box = el("details", "thinking");
    box.open = showThinking;
    const label = el("summary", undefined, "Thinking…");
    const body = el("pre", "thinking-body");
    box.append(label, body);
    t = { box, label, body, text: "", started: Date.now(), done: false };
    thinking.set(key, t);
    append(box);
  }
  return t;
}

function finishThinking(t: Thinking): void {
  if (t.done) return;
  t.done = true;
  const secs = Math.max(1, Math.round((Date.now() - t.started) / 1000));
  t.label.textContent = `Thought for ${secs} s`;
  if (!t.text) {
    // The CLI may send only a signature, no readable reasoning: say so instead of an empty box.
    t.box.classList.add("no-text");
    t.body.textContent = "Claude didn't share the text of this reasoning.";
  }
}

function setShowThinking(show: boolean): void {
  showThinking = show;
  thinking.forEach((t) => (t.box.open = show));
}

// ---------- to-do list ----------
const TODO_MARK: Record<string, string> = { pending: "○", in_progress: "▶", completed: "●" };

function showTodos(items: { text: string; status: string }[]): void {
  todoList.replaceChildren(
    ...items.map((i) => {
      const li = el("li", `todo ${i.status}`);
      li.append(el("span", "todo-mark", TODO_MARK[i.status] ?? "○"), el("span", "todo-text", i.text));
      return li;
    }),
  );
  const done = items.filter((i) => i.status === "completed").length;
  todoLabel.textContent = `To-do ${done}/${items.length}`;
  todoBox.hidden = items.length === 0;
}

// Task tools (TaskCreate / TaskUpdate) build the same list one item at a time, keyed by the id the CLI assigns.
type Task = { text: string; activeForm?: string; status: string };
const tasks = new Map<string, Task>();
const pendingTasks = new Map<string, Task>(); // by TaskCreate tool_use id, until its result gives the task id

function showTasks(): void {
  showTodos([...tasks.values()].map((t) => ({ text: t.status === "in_progress" && t.activeForm ? t.activeForm : t.text, status: t.status })));
}

// ---------- queue ----------
function renderQueue(): void {
  queueBox.replaceChildren(
    ...queue.map((text, i) => {
      const row = el("div", "queued");
      const remove = el("button", "link-button", "×");
      remove.title = "Remove from queue";
      remove.addEventListener("click", () => {
        queue.splice(i, 1);
        renderQueue();
      });
      row.append(el("span", "queued-label", "Queued"), el("span", "queued-text", text), remove);
      return row;
    }),
  );
  queueBox.hidden = queue.length === 0;
}

// ---------- sending ----------
function userMessage(text: string): void {
  const m = el("div", "msg user");
  m.append(el("div", "user-text", text), el("div", "time", clock()));
  append(m, true);
}

function dispatch(text: string): void {
  userMessage(text);
  turnTexts = [];
  setBusy(true);
  post({ type: "send", text });
}

function submit(): void {
  const text = input.value.trim();
  if (!text) return;
  input.value = "";
  if (busy) {
    queue.push(text);
    renderQueue();
    setBusy(true);
  } else {
    dispatch(text);
  }
}

sendButton.addEventListener("click", () => (busy && !input.value.trim() ? post({ type: "interrupt" }) : submit()));
input.addEventListener("input", () => {
  if (busy) setBusy(true); // Stop <-> Queue label
});
input.addEventListener("keydown", (ev) => {
  if (ev.key === "Enter" && !ev.shiftKey && !ev.isComposing) {
    ev.preventDefault();
    submit();
  }
});
document.addEventListener("keydown", (ev) => {
  if (ev.key === "Escape" && busy) {
    ev.preventDefault();
    post({ type: "interrupt" });
  }
  // Ctrl+O is registered in Kotlin as a panel shortcut (the IDE keymap gets it first) and arrives as toggleThinking.
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

// The embedded browser doesn't change the IDE's mouse cursor, so ask Kotlin for the hand over clickable things.
let cursorShape: "pointer" | "default" = "default";
document.addEventListener("mouseover", (ev) => {
  const shape = (ev.target as Element | null)?.closest("a, button, summary") ? "pointer" : "default";
  if (shape === cursorShape) return;
  cursorShape = shape;
  post({ type: "cursor", shape });
});

/** Footer of a turn: time, duration, cost and a Copy button for the turn's text (Markdown source). */
function turnFooter(parts: string[]): HTMLElement {
  const meta = el("div", "meta");
  meta.append(el("span", undefined, [clock(), ...parts].join(" · ")));
  const text = turnTexts.map((k) => textBlocks.get(k)?.text ?? "").filter((t) => t).join("\n\n");
  if (text) {
    const copy = el("button", "link-button copy", "Copy");
    copy.addEventListener("click", () => {
      post({ type: "copy", text });
      copy.textContent = "Copied";
      setTimeout(() => (copy.textContent = "Copy"), 1500);
    });
    meta.append(copy);
  }
  return meta;
}

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
      const key = unfinished.get(msg.messageId)?.shift() ?? msg.key;
      const b = textBlock(key);
      b.text = msg.text;
      renderBlock(key);
      break;
    }
    case "thinkingStart":
      thinkingBlock(msg.key);
      break;
    case "thinkingDelta": {
      const t = thinkingBlock(msg.key);
      t.text += msg.text;
      t.body.textContent = t.text; // plain text, never HTML
      break;
    }
    case "blockStop": {
      const t = thinking.get(msg.key);
      if (t) finishThinking(t);
      break;
    }
    case "todos":
      showTodos(msg.items);
      break;
    case "taskCreate":
      pendingTasks.set(msg.toolUseId, { text: msg.subject, activeForm: msg.activeForm, status: "pending" });
      break;
    case "taskCreated": {
      const t = pendingTasks.get(msg.toolUseId);
      pendingTasks.delete(msg.toolUseId);
      if (t) {
        tasks.set(msg.taskId, t);
        showTasks();
      }
      break;
    }
    case "taskUpdate": {
      if (msg.status === "deleted") tasks.delete(msg.taskId);
      const t = tasks.get(msg.taskId);
      if (t) {
        if (msg.status) t.status = msg.status;
        if (msg.activeForm) t.activeForm = msg.activeForm;
        if (msg.subject) t.text = msg.subject;
      }
      showTasks();
      break;
    }
    case "toggleThinking":
      setShowThinking(!showThinking);
      break;
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
      thinking.forEach(finishThinking); // an interrupt can leave a block without its stop event
      const parts: string[] = [];
      if (msg.durationMs) parts.push(`${(msg.durationMs / 1000).toFixed(1)} s`);
      if (msg.costUsd) parts.push(`$${msg.costUsd.toFixed(4)}`);
      if (msg.isError) parts.push("ended with an error");
      append(turnFooter(parts));
      setBusy(false);
      const next = queue.shift();
      if (next !== undefined) {
        renderQueue();
        dispatch(next);
      }
      break;
    }
    case "sessionReset":
      textBlocks.clear();
      unfinished.clear();
      toolCards.clear();
      thinking.clear();
      turnTexts = [];
      queue.length = 0;
      renderQueue();
      tasks.clear();
      pendingTasks.clear();
      showTodos([]);
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
