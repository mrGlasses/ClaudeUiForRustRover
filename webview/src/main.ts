// Chat panel. 7a: send prompts, stream Claude's answer as sanitized Markdown, tool calls, stop a turn.
// 7b: thinking blocks (Ctrl+O), to-do list, queued messages, timestamps, copy a response.
// 7c: Focus view (Ctrl+Alt+F), date lines + timestamp setting, /copy, the `/` menu, LaTeX (markdown.ts), sign-in card.
// Text from Claude goes through renderMarkdown (sanitized); everything else uses textContent.
import { onMessage, post, type FromKotlin } from "./bridge";
import { renderMarkdown } from "./markdown";
import { clearCards, modeControl, pendingCount, permissionCard, resolveCard } from "./permissions";
import { createHistory, type HistoryItem } from "./history";
import { createAgentMap } from "./agents";
import { createModelBar } from "./modelbar";

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
const focusButton = el("button", "link-button", "Focus");
focusButton.title = "Hide tool calls and thinking (Ctrl+Alt+F)";
const newButton = el("button", "link-button", "New session");
const headerButtons = el("span", "header-buttons");
const historyButton = el("button", "link-button", "History");
historyButton.title = "Past conversations in this project";
headerButtons.append(historyButton, focusButton, newButton);
const modes = modeControl();
const headerLeft = el("span", "header-left");
headerLeft.append(status, modes.node);
header.append(headerLeft, headerButtons);

const transcript = el("div", "transcript");
const empty = el("div", "empty", "Ask Claude about this project. Enter sends, Shift+Enter adds a line, Esc stops, / opens commands, @ mentions a file or terminal, paste images or drop files here, Ctrl+O shows thinking, Ctrl+Alt+F toggles Focus view.");
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

// `/` menu: panel commands plus the CLI's (from system/init, known after the first message).
const slashMenu = el("div", "slash-menu");
slashMenu.hidden = true;

const composer = el("div", "composer");
const input = el("textarea", "input");
input.rows = 3;
input.placeholder = "Message Claude…";
const sendButton = el("button", "send", "Send");
composer.append(input, sendButton);
// Phase 9: chips for what goes with the next message (open file, selection); X drops one until the editor changes.
const chips = el("div", "chips");
chips.hidden = true;
const agentMap = createAgentMap();
// Phase 12a: model / effort / thinking, context meter (click: /compact), prompt-cache clock.
const modelBar = createModelBar({
  compact: () => dispatch({ text: "/compact", files: [], images: [], includeFile: false, includeSelection: false }),
  isBusy: () => busy,
});
root.append(header, transcript, agentMap.node, todoBox, queueBox, slashMenu, chips, composer, modelBar.node);

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
// A message as it will be sent: chips and attachments are taken when you press Enter, also for queued messages.
type Img = { mediaType: string; data: string; name: string };
type Outgoing = { text: string; files: string[]; images: Img[]; includeFile: boolean; includeSelection: boolean; uuid?: string };

// ---------- checkpoints (Phase 11a) ----------
/** A v4 UUID for each prompt: it becomes the prompt's restore point in the CLI (Kotlin checks the format again). */
function newUuid(): string {
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

const CHECKPOINT_ACTIONS: [("fork" | "rewind" | "forkRewind"), string][] = [
  ["fork", "Fork conversation from here"],
  ["rewind", "Rewind code to here"],
  ["forkRewind", "Fork conversation and rewind code"],
];
let openCheckpointMenu: HTMLElement | null = null;

/** The ↶ button on a prompt (shown on hover) and its menu. Kotlin decides; rewinding asks in a native dialog. */
function checkpointButton(uuid: string): HTMLElement {
  const wrap = el("span", "checkpoint");
  const button = el("button", "link-button checkpoint-button", "↶");
  button.title = "Fork or rewind from this message";
  const menu = el("div", "checkpoint-menu");
  menu.hidden = true;
  for (const [action, label] of CHECKPOINT_ACTIONS) {
    const item = el("button", "mode-item", label);
    item.addEventListener("click", (ev) => {
      ev.stopPropagation();
      menu.hidden = true;
      openCheckpointMenu = null;
      post({ type: "checkpoint", action, uuid });
    });
    menu.append(item);
  }
  button.addEventListener("click", (ev) => {
    ev.stopPropagation();
    if (busy) return;
    if (openCheckpointMenu && openCheckpointMenu !== menu) openCheckpointMenu.hidden = true;
    menu.hidden = !menu.hidden;
    openCheckpointMenu = menu.hidden ? null : menu;
  });
  wrap.append(button, menu);
  return wrap;
}
document.addEventListener("click", () => {
  if (openCheckpointMenu) openCheckpointMenu.hidden = true;
  openCheckpointMenu = null;
});
const queue: Outgoing[] = [];
let attachedFiles: string[] = []; // dropped files (Phase 9b), sent as @mentions
let attachedImages: Img[] = []; // pasted images (Phase 9b)
const turnHistory: string[] = []; // each finished turn's text, for /copy
let lastDay = "";
let cliCommands: string[] = [];
const PANEL_COMMANDS: { name: string; hint: string }[] = [
  { name: "copy", hint: "copy the last response (/copy 2: the one before)" },
  { name: "clear", hint: "start a new session" },
  { name: "focus", hint: "toggle Focus view" },
  { name: "export", hint: "save this conversation as Markdown (/export copy: copy it)" },
  { name: "resume", hint: "open the conversation history" },
  { name: "tasks", hint: "show agents and background tasks" },
  { name: "model", hint: "pick the model, effort and extended thinking" },
];

function setBusy(b: boolean): void {
  busy = b;
  const typing = input.value.trim() !== "";
  sendButton.textContent = b ? (typing ? "Queue" : "Stop") : "Send";
  sendButton.classList.toggle("stop", b && !typing);
  status.textContent = b
    ? pendingCount() > 0 ? "Waiting for you…" : `Working${model ? ` · ${model}` : ""}…`
    : model ? `Ready · ${model}` : "Ready";
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
    ...queue.map((o, i) => {
      const row = el("div", "queued");
      const remove = el("button", "link-button", "×");
      remove.title = "Remove from queue";
      remove.addEventListener("click", () => {
        queue.splice(i, 1);
        renderQueue();
      });
      row.append(el("span", "queued-label", "Queued"), el("span", "queued-text", o.text), remove);
      return row;
    }),
  );
  queueBox.hidden = queue.length === 0;
}

// ---------- sending ----------
function dayLine(): void {
  const d = new Date();
  const day = d.toDateString();
  if (day === lastDay) return;
  lastDay = day;
  append(el("div", "day-line", d.toLocaleDateString([], { weekday: "long", day: "numeric", month: "long", year: "numeric" })));
}

function userMessage(o: Outgoing): void {
  dayLine();
  const m = el("div", "msg user");
  if (o.uuid) m.append(checkpointButton(o.uuid));
  m.append(el("div", "user-text", o.text));
  if (o.images.length || o.files.length) {
    const att = el("div", "user-attachments");
    o.images.forEach((img) => {
      const i = el("img", "thumb");
      i.src = `data:${img.mediaType};base64,${img.data}`; // CSP allows data: images only
      i.alt = img.name;
      att.append(i);
    });
    o.files.forEach((f) => att.append(el("span", "chip-ctx", `📎 ${f}`)));
    m.append(att);
  }
  m.append(el("div", "time", clock()));
  append(m, true);
}

/** Takes the attachments and chips as they are now, and clears the attachments. */
function outgoing(text: string): Outgoing {
  const o = { text, files: attachedFiles, images: attachedImages, includeFile: fileChipOn(), includeSelection: selectionChipOn() };
  attachedFiles = [];
  attachedImages = [];
  renderChips();
  return o;
}

function dispatch(o: Outgoing): void {
  o.uuid = newUuid();
  userMessage(o);
  turnTexts = [];
  setBusy(true);
  // Dropped files travel as @mentions after the prompt, like typed ones.
  const text = o.files.length ? `${o.text}\n\n${o.files.map((f) => `@${f}`).join(" ")}` : o.text;
  post({
    type: "send",
    text,
    uuid: o.uuid,
    includeFile: o.includeFile,
    includeSelection: o.includeSelection,
    images: o.images.map((i) => ({ mediaType: i.mediaType, data: i.data })),
  });
}

// ---------- context chips ----------
type Ctx = { file?: string; showFile?: boolean; startLine?: number; endLine?: number; textShared?: boolean };
let ctx: Ctx = {};
let droppedFile = ""; // the chip key the user closed with X; a different file or selection brings chips back
let droppedSelection = "";
const fileKey = () => ctx.file ?? "";
const selectionKey = () => (ctx.startLine ? `${ctx.file}#${ctx.startLine}-${ctx.endLine}` : "");
const fileChipOn = () => !!ctx.showFile && fileKey() !== droppedFile;
const selectionChipOn = () => !!ctx.startLine && selectionKey() !== droppedSelection;

function chip(label: string, title: string, onClose: () => void): HTMLElement {
  const c = el("span", "chip-ctx");
  c.title = title;
  const x = el("button", "chip-x", "×");
  x.title = "Don't send this with the next message";
  x.addEventListener("click", onClose);
  c.append(el("span", undefined, label), x);
  return c;
}

function renderChips(): void {
  const items: HTMLElement[] = [];
  const name = (ctx.file ?? "").split("/").pop() ?? "";
  if (fileChipOn()) items.push(chip(`📄 ${name}`, `Open file: ${ctx.file}`, () => { droppedFile = fileKey(); renderChips(); }));
  if (selectionChipOn()) {
    const n = (ctx.endLine ?? 0) - (ctx.startLine ?? 0) + 1;
    const lines = ctx.startLine === ctx.endLine ? `${ctx.startLine}` : `${ctx.startLine}-${ctx.endLine}`;
    const label = `✂ ${name}:${lines} (${n} line${n === 1 ? "" : "s"}${ctx.textShared ? "" : ", text not shared"})`;
    items.push(chip(label, ctx.textShared ? "Selected text goes with the next message" : "Only the line numbers go (settings or ignore rules)", () => {
      droppedSelection = selectionKey();
      renderChips();
    }));
  }
  attachedFiles.forEach((f, i) =>
    items.push(chip(`📎 ${f}`, `Attached: ${f}`, () => { attachedFiles.splice(i, 1); renderChips(); })),
  );
  attachedImages.forEach((img, i) => {
    const c = chip(`🖼 ${img.name}`, "Pasted image", () => { attachedImages.splice(i, 1); renderChips(); });
    const t = el("img", "chip-thumb");
    t.src = `data:${img.mediaType};base64,${img.data}`;
    c.prepend(t);
    items.push(c);
  });
  chips.replaceChildren(...items);
  chips.hidden = items.length === 0;
}

// ---------- pasted images (Phase 9b) ----------
const IMAGE_TYPES = ["image/png", "image/jpeg", "image/gif", "image/webp"];
const IMAGE_MAX_BYTES = 5 * 1024 * 1024;

input.addEventListener("paste", (ev) => {
  const files = [...(ev.clipboardData?.items ?? [])].filter((i) => i.kind === "file" && IMAGE_TYPES.includes(i.type));
  if (files.length === 0) return; // plain text paste
  ev.preventDefault();
  for (const item of files) {
    const f = item.getAsFile();
    if (!f) continue;
    if (attachedImages.length >= 5) {
      append(el("div", "notice", "Up to 5 images per message."));
      break;
    }
    if (f.size > IMAGE_MAX_BYTES) {
      append(el("div", "notice", "That image is over 5 MB."));
      continue;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const url = String(reader.result);
      const comma = url.indexOf(",");
      attachedImages.push({ mediaType: f.type, data: url.slice(comma + 1), name: `image ${attachedImages.length + 1}` });
      renderChips();
    };
    reader.readAsDataURL(f);
  }
});

function insertAtCaret(text: string): void {
  const s = input.selectionStart ?? input.value.length;
  const e = input.selectionEnd ?? s;
  const before = input.value.slice(0, s);
  const pad = before && !/\s$/.test(before) ? " " : "";
  input.value = before + pad + text + input.value.slice(e);
  const caret = (before + pad + text).length;
  input.setSelectionRange(caret, caret);
  input.focus();
}

/** Panel commands run here and never reach Claude. Returns true when [text] was one. */
function panelCommand(text: string): boolean {
  const copy = /^\/copy(?:\s+(\d+))?$/.exec(text);
  if (copy) {
    const n = copy[1] ? Number(copy[1]) : 1;
    const t = turnHistory[turnHistory.length - n];
    if (t) post({ type: "copy", text: t });
    append(el("div", "notice", t ? `Copied response ${n === 1 ? "" : `${n} back `}to the clipboard.` : "Nothing to copy yet."));
    return true;
  }
  if (text === "/clear") {
    post({ type: "newSession" });
    return true;
  }
  const exp = /^\/export(?:\s+(copy))?$/.exec(text);
  if (exp) {
    post({ type: "export", mode: exp[1] ? "copy" : "file" });
    return true;
  }
  if (text === "/resume") {
    openHistory();
    return true;
  }
  if (text === "/focus") {
    toggleFocus();
    return true;
  }
  if (text === "/model") {
    modelBar.open();
    return true;
  }
  if (text === "/tasks") {
    if (!agentMap.show()) append(el("div", "notice", "No agents or background tasks in this conversation yet."));
    return true;
  }
  return false;
}

function submit(): void {
  const text = input.value.trim();
  if (!text) return;
  input.value = "";
  closeMenu();
  if (panelCommand(text)) return;
  const o = outgoing(text);
  if (busy) {
    queue.push(o);
    renderQueue();
    setBusy(true);
  } else {
    dispatch(o);
  }
}

sendButton.addEventListener("click", () => (busy && !input.value.trim() ? post({ type: "interrupt" }) : submit()));
input.addEventListener("input", () => {
  if (busy) setBusy(true); // Stop <-> Queue label
  updateMenu();
});
input.addEventListener("keydown", (ev) => {
  if (!slashMenu.hidden && menuKey(ev)) return;
  if (ev.key === "Enter" && !ev.shiftKey && !ev.isComposing) {
    ev.preventDefault();
    submit();
  }
});
document.addEventListener("keydown", (ev) => {
  if (ev.key === "Escape" && !slashMenu.hidden) {
    ev.preventDefault();
    closeMenu();
  } else if (ev.key === "Escape" && busy) {
    ev.preventDefault();
    post({ type: "interrupt" });
  }
  // Ctrl+O is registered in Kotlin as a panel shortcut (the IDE keymap gets it first) and arrives as toggleThinking.
});
newButton.addEventListener("click", () => post({ type: "newSession" }));
focusButton.addEventListener("click", () => toggleFocus());

// ---------- history (Phase 10, history.ts) ----------
let resumedNote = "";
let forkNote = false;
const history = createHistory(() => closeHistory());
transcript.before(history.view);
function openHistory(): void {
  transcript.hidden = true;
  history.open();
}
function closeHistory(): void {
  history.view.hidden = true;
  transcript.hidden = false;
  input.focus();
}
historyButton.addEventListener("click", () => (history.view.hidden ? openHistory() : closeHistory()));

// ---------- Focus view ----------
function toggleFocus(): void {
  const on = transcript.classList.toggle("focus");
  focusButton.textContent = on ? "Focus on" : "Focus";
}

// ---------- `/` and `@` menu ----------
let menuItems: { name: string; hint: string }[] = [];
let menuIndex = 0;
let menuMode: "slash" | "mention" = "slash";
let mentionSeq = 0;

/** The `@word` right before the caret, if any. */
function mentionToken(): { start: number; query: string } | null {
  const caret = input.selectionStart ?? input.value.length;
  const m = /(^|\s)@([^\s@]*)$/.exec(input.value.slice(0, caret));
  return m ? { start: caret - m[2].length - 1, query: m[2] } : null;
}

function updateMenu(): void {
  const mention = mentionToken();
  if (mention) {
    post({ type: "mentionQuery", query: mention.query, seq: ++mentionSeq });
    return; // results arrive as mentionResults
  }
  menuMode = "slash";
  const m = /^\/(\S*)$/.exec(input.value);
  if (!m) return closeMenu();
  const q = m[1].toLowerCase();
  const all = [...PANEL_COMMANDS, ...cliCommands.filter((c) => !PANEL_COMMANDS.some((p) => p.name === c)).map((name) => ({ name, hint: "" }))];
  menuItems = all.filter((c) => c.name.toLowerCase().includes(q)).slice(0, 50);
  if (menuItems.length === 0) return closeMenu();
  menuIndex = Math.min(menuIndex, menuItems.length - 1);
  renderMenu();
}

function renderMenu(): void {
  slashMenu.replaceChildren(
    ...menuItems.map((c, i) => {
      const row = el("button", `slash-item${i === menuIndex ? " active" : ""}`);
      row.append(el("span", "slash-name", `${menuMode === "slash" ? "/" : "@"}${c.name}`), el("span", "slash-hint", c.hint));
      row.addEventListener("mousedown", (ev) => {
        ev.preventDefault(); // keep the focus in the input
        pickMenu(i);
      });
      return row;
    }),
  );
  slashMenu.hidden = false;
  slashMenu.querySelector(".active")?.scrollIntoView({ block: "nearest" });
}

function pickMenu(i: number): void {
  if (menuMode === "mention") {
    const t = mentionToken();
    const caret = input.selectionStart ?? input.value.length;
    if (t) {
      const path = menuItems[i].name;
      const insert = `@${path}${path.endsWith("/") ? "" : " "}`;
      input.value = input.value.slice(0, t.start) + insert + input.value.slice(caret);
      const pos = t.start + insert.length;
      input.setSelectionRange(pos, pos);
    }
    closeMenu();
    input.focus();
    if (menuItems[i]?.name.endsWith("/")) updateMenu(); // keep browsing inside the folder
    return;
  }
  input.value = `/${menuItems[i].name} `;
  closeMenu();
  input.focus();
}

function closeMenu(): void {
  slashMenu.hidden = true;
  menuIndex = 0;
}

/** Arrow keys move, Enter/Tab pick, while the menu is open. */
function menuKey(ev: KeyboardEvent): boolean {
  if (ev.key === "ArrowDown" || ev.key === "ArrowUp") {
    ev.preventDefault();
    menuIndex = (menuIndex + (ev.key === "ArrowDown" ? 1 : menuItems.length - 1)) % menuItems.length;
    renderMenu();
    return true;
  }
  if ((ev.key === "Enter" && !ev.shiftKey) || ev.key === "Tab") {
    ev.preventDefault();
    pickMenu(menuIndex);
    return true;
  }
  return false;
}

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
  const shape = (ev.target as Element | null)?.closest("a, button, summary, label, select") ? "pointer" : "default";
  if (shape === cursorShape) return;
  cursorShape = shape;
  post({ type: "cursor", shape });
});

/** Footer of a turn: time, duration, cost and a Copy button for the turn's text (Markdown source). */
function turnFooter(parts: string[]): HTMLElement {
  const meta = el("div", "meta");
  meta.append(el("span", "time", clock()), el("span", undefined, parts.join(" · ")));
  const text = turnTexts.map((k) => textBlocks.get(k)?.text ?? "").filter((t) => t).join("\n\n");
  if (text) {
    turnHistory.push(text);
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
  if (msg.type.startsWith("agent") && agentMap.handle(msg as { type: string } & Record<string, unknown>)) return;
  if (modelBar.handle(msg as { type: string } & Record<string, unknown>)) return;
  switch (msg.type) {
    case "hello":
      status.textContent = "Ready";
      break;
    case "settings":
      root.classList.toggle("no-times", !msg.showTimestamps);
      modes.setModes(msg.modes);
      break;
    case "permissionMode":
      modes.setMode(msg.mode);
      break;
    case "permission":
      append(permissionCard(msg), true);
      setBusy(true);
      break;
    case "permissionResolved":
      resolveCard(msg.requestId, msg.outcome);
      if (busy) setBusy(true);
      break;
    case "sessionInfo":
      model = msg.model;
      modelBar.sessionModel(msg.model);
      if (msg.permissionMode) modes.setMode(msg.permissionMode);
      cliCommands = msg.slashCommands ?? [];
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
    case "context":
      ctx = msg;
      renderChips();
      break;
    case "insertText":
      insertAtCaret(msg.text);
      break;
    case "attachFiles":
      for (const p of msg.paths) if (!attachedFiles.includes(p)) attachedFiles.push(p);
      renderChips();
      input.focus();
      break;
    case "mentionResults": {
      if (msg.seq !== mentionSeq || !mentionToken()) break; // an older query, or the @ is gone
      menuMode = "mention";
      menuItems = msg.items.map((p) => ({ name: p, hint: p.endsWith("/") ? "folder" : p.startsWith("terminal:") ? "terminal output" : "" }));
      if (menuItems.length === 0) {
        closeMenu();
        break;
      }
      menuIndex = Math.min(menuIndex, menuItems.length - 1);
      renderMenu();
      break;
    }
    case "historyItems":
      history.render(msg.items, msg.groups ?? [], msg.filter ?? "ACTIVE");
      break;
    case "historyLoaded":
      // Said at the end (historyEnd), where you look after the replay; at the top it scrolled out of sight (v0.22.0 test).
      resumedNote = msg.fork
        ? `Forked from "${msg.title}", before the message now in the box below. Edit it and send to continue on this new branch; the original conversation stays as it was.`
        : msg.automatic ? `Continuing "${msg.title}" (Settings > Tools > Clawed UI to turn this off).` : `Resumed "${msg.title}".`;
      forkNote = msg.fork === true;
      break;
    case "historyUser": {
      const m = el("div", "msg user");
      if (msg.uuid) m.append(checkpointButton(msg.uuid));
      m.append(el("div", "user-text", msg.text));
      if (msg.timestamp) m.append(el("div", "time", new Date(msg.timestamp).toLocaleString([], { dateStyle: "short", timeStyle: "short" })));
      append(m, true);
      turnTexts = [];
      break;
    }
    case "historyEnd":
      transcript.scrollTop = transcript.scrollHeight;
      append(el("div", "notice", forkNote ? resumedNote : `${resumedNote} Your next message continues this conversation.`.trim()), true);
      resumedNote = "";
      forkNote = false;
      break;
    case "toggleFocus":
      toggleFocus();
      break;
    case "signIn": {
      if (transcript.querySelector(".sign-in:last-child")) break; // one card per turn is enough
      const card = el("div", "sign-in");
      const button = el("button", "send", "Sign in in a terminal");
      button.addEventListener("click", () => post({ type: "signIn" }));
      card.append(
        el("div", "sign-in-title", "Claude Code isn't signed in."),
        el("div", undefined, "Signing in is interactive, so it runs in a Claude terminal tab: type /login there, then come back and click New session."),
        button,
      );
      append(card, true);
      break;
    }
    case "toolUse": {
      const card = el("div", "tool");
      card.append(el("span", "tool-name", msg.name), el("span", "tool-summary", msg.summary), el("span", "tool-state", "…"));
      if (msg.canBackground) {
        // Phase 11b: like Ctrl+B in the terminal, for this one call; Kotlin checks it's still running.
        const bg = el("button", "link-button tool-bg", "Run in background");
        bg.addEventListener("click", () => {
          bg.remove();
          post({ type: "taskAction", action: "background", id: msg.id });
        });
        card.append(bg);
      }
      toolCards.set(msg.id, card);
      append(card);
      break;
    }
    case "toolResult": {
      const card = toolCards.get(msg.toolUseId);
      if (!card) break;
      card.querySelector(".tool-bg")?.remove();
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
      modelBar.turnEnded(msg.cacheTtlMin);
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
      turnHistory.length = 0;
      clearCards();
      lastDay = "";
      queue.length = 0;
      renderQueue();
      tasks.clear();
      pendingTasks.clear();
      showTodos([]);
      agentMap.clear();
      modelBar.reset();
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
