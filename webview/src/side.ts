// Phase 14b: `/btw` side questions in a box beside the conversation (newest 20 kept), and the bookmarks card.
// Answers come from the CLI's side_question (they don't enter the conversation); rendered as sanitized Markdown (rule 1).
import { post } from "./bridge";
import { renderMarkdown } from "./markdown";

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

const KEEP = 20;

export function createSideQuestions() {
  const box = el("details", "agents side-questions");
  box.open = true;
  box.hidden = true;
  const summary = el("summary");
  const label = el("span", "agents-label", "Side questions (/btw)");
  const hide = el("button", "link-button todo-hide", "Hide");
  hide.addEventListener("click", (ev) => {
    ev.preventDefault();
    box.hidden = true;
  });
  summary.append(label, hide);
  const list = el("div", "agents-list");
  const ask = el("div", "customize-head side-ask");
  const input = el("input", "history-search");
  input.placeholder = "Ask on the side (doesn't go into the conversation)…";
  const go = el("button", "link-button", "Ask");
  const submit = () => {
    const q = input.value.trim();
    if (!q) return input.focus();
    input.value = "";
    question(q);
  };
  go.addEventListener("click", submit);
  input.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter") {
      ev.preventDefault();
      submit();
    }
  });
  ask.append(input, go);
  box.append(summary, list, ask);
  const pending = new Map<string, HTMLElement>();

  function question(q: string): void {
    box.hidden = false;
    box.open = true;
    const item = el("div", "side-item");
    item.append(el("div", "side-q", q), el("div", "muted", "Thinking…"));
    list.append(item);
    while (list.children.length > KEEP) list.firstElementChild?.remove();
    pending.set(q, item);
    post({ type: "sideQuestion", question: q } as never);
    list.scrollTop = list.scrollHeight;
  }

  return {
    node: box,
    ask: question,
    answer(q: string, answer: string, error?: string): void {
      const item = pending.get(q);
      if (!item) return;
      pending.delete(q);
      item.lastElementChild?.remove();
      if (error) item.append(el("div", "agent-error", error));
      else {
        const a = el("div", "side-a markdown");
        a.innerHTML = renderMarkdown(answer); // sanitized
        item.append(a);
      }
      list.scrollTop = list.scrollHeight;
    },
    clear(): void {
      list.replaceChildren();
      pending.clear();
      box.hidden = true;
    },
  };
}

export type Bookmark = { id: string; session: string; title: string; text: string; time: number };

export function bookmarksCard(items: Bookmark[]): HTMLElement {
  const card = el("div", "info-card");
  card.append(el("div", "info-title", `Bookmarks (${items.length})`));
  if (!items.length) card.append(el("div", "muted", "No bookmarks yet: click ☆ under a response to keep it."));
  for (const b of items) {
    const row = el("details", "bookmark");
    const head = el("summary");
    head.append(el("span", "customize-name", b.title), el("span", "muted", ` · ${new Date(b.time).toLocaleString([], { dateStyle: "short", timeStyle: "short" })} · `), el("span", "muted", b.text.split("\n")[0].slice(0, 120)));
    const body = el("div", "markdown bookmark-text");
    body.innerHTML = renderMarkdown(b.text); // sanitized
    const actions = el("div", "customize-head");
    const act = (label: string, action: string, cls = "link-button") => {
      const btn = el("button", cls, label);
      btn.addEventListener("click", () => post({ type: "bookmarkAction", id: b.id, action } as never));
      actions.append(btn);
    };
    act("Open conversation", "open");
    act("Copy", "copy");
    act("Remove", "remove", "link-button deny");
    row.append(head, body, actions);
    card.append(row);
  }
  return card;
}

/** The getting-started list shown in an empty conversation (VS Code's onboarding), until "Don't show again". */
export function onboarding(run: (command: string) => void, insert: (text: string) => void): HTMLElement {
  const box = el("div", "onboarding");
  box.append(el("div", "info-title", "Getting started"));
  const steps: [string, () => void][] = [
    ["Ask about this project: “What does this project do? Where should I start reading?”", () => insert("What does this project do? Where should I start reading?")],
    ["Mention a file: type @ and a few letters of its name", () => insert("@")],
    ["Plan before changing code: pick “Plan mode” in the mode menu at the top", () => undefined],
    ["Select code in the editor and press Alt+K (or the ✻ button) to bring it here", () => undefined],
    ["Commands, skills, hooks, permissions and more: /customize", () => run("/customize")],
    ["Your past conversations: History (or /resume)", () => run("/resume")],
  ];
  const ul = el("ul", "usage-list");
  for (const [text, action] of steps) {
    const li = el("li");
    const b = el("button", "link-button onboarding-step", text);
    b.addEventListener("click", action);
    li.append(b);
    ul.append(li);
  }
  const hide = el("button", "link-button", "Don't show again");
  hide.addEventListener("click", () => {
    box.remove();
    post({ type: "hideOnboarding" } as never);
  });
  box.append(ul, hide);
  return box;
}
