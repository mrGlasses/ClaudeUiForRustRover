// Phase 8: permission cards (tool approval, Claude's questions, plan approval) and the permission-mode menu.
// The page only *asks*: Kotlin checks every answer against the pending request (rule 4) and confirms permanent rules and
// Bypass in a native dialog (rule 5). Text from Claude is shown with textContent, the plan through renderMarkdown.
import { post } from "./bridge";
import { renderMarkdown } from "./markdown";

export type Suggestion = { index: number; label: string; destination: string; canChangeDestination: boolean };
export type Question = { question: string; header: string; multiSelect: boolean; options: { label: string; description: string }[] };
export type PermissionCard = {
  type: "permission";
  requestId: string;
  toolName: string;
  displayName: string;
  kind: "tool" | "question" | "plan";
  description?: string;
  reason?: string;
  preview?: string;
  plan?: string;
  questions?: Question[];
  suggestions: Suggestion[];
};

const DESTINATIONS: [string, string][] = [
  ["session", "this session"],
  ["localSettings", "this project (just you)"],
  ["projectSettings", "this project (shared)"],
  ["userSettings", "all projects"],
];

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

const cards = new Map<string, HTMLElement>();

export function pendingCount(): number {
  return [...cards.values()].filter((c) => !c.classList.contains("resolved")).length;
}

/** Builds the card for one request. Every button sends one answer and then waits for Kotlin's `permissionResolved`. */
export function permissionCard(p: PermissionCard): HTMLElement {
  const card = el("div", `permission perm-${p.kind}`);
  cards.set(p.requestId, card);
  const answer = (body: Record<string, unknown>) => {
    card.classList.add("answering");
    post({ type: "permissionAnswer", requestId: p.requestId, ...body } as never);
  };

  const title = p.kind === "question" ? "Claude has a question" : p.kind === "plan" ? "Claude's plan" : `Allow ${p.displayName}?`;
  card.append(el("div", "permission-title", title));
  if (p.description && p.kind === "tool") card.append(el("div", "permission-target", p.description));
  if (p.reason) card.append(el("div", "permission-reason", p.reason));

  if (p.kind === "tool" && p.preview) card.append(el("pre", "permission-preview", p.preview));
  if (p.kind === "plan") {
    const plan = el("div", "permission-plan markdown");
    plan.innerHTML = renderMarkdown(p.plan ?? ""); // sanitized
    card.append(plan);
  }

  const actions = el("div", "permission-actions");
  if (p.kind === "question") {
    const forms = (p.questions ?? []).map((q, qi) => questionBlock(q, qi, p.requestId));
    forms.forEach((f) => card.append(f.node));
    const submit = el("button", "send", "Answer");
    submit.addEventListener("click", () => {
      const answers: Record<string, string | string[]> = {};
      forms.forEach((f) => {
        const v = f.value();
        if (v !== null) answers[f.question] = v;
      });
      if (Object.keys(answers).length > 0) answer({ decision: "allow", answers });
    });
    actions.append(submit);
  } else {
    const yes = el("button", "send", p.kind === "plan" ? "Approve plan" : "Yes");
    yes.addEventListener("click", () => answer({ decision: "allow" }));
    actions.append(yes);
    for (const s of p.suggestions) {
      const row = el("span", "suggestion");
      const b = el("button", "send", s.label);
      let select: HTMLSelectElement | null = null;
      if (s.canChangeDestination) {
        select = el("select", "destination");
        select.title = "Save to";
        for (const [id, label] of DESTINATIONS) {
          const o = el("option", undefined, label);
          o.value = id;
          o.selected = id === s.destination;
          select.append(o);
        }
      }
      b.addEventListener("click", () => answer({ decision: "allow", suggestion: s.index, destination: select?.value }));
      row.append(b);
      if (select) row.append(el("span", "muted", " save to "), select);
      actions.append(row);
    }
  }
  const no = el("button", "send deny", p.kind === "question" ? "Don't answer" : "No");
  no.addEventListener("click", () => answer({ decision: "deny" }));
  actions.append(no);
  card.append(actions);

  // "Tell Claude what to do instead" (tools), plan feedback, or a free reply instead of the questions.
  const other = el("div", "permission-other");
  const text = el("textarea", "input");
  text.rows = 2;
  text.placeholder = p.kind === "plan" ? "Keep planning: what should change?" : p.kind === "question" ? "Or reply in your own words…" : "No, and tell Claude what to do instead…";
  const send = el("button", "send", p.kind === "plan" ? "Keep planning" : p.kind === "question" ? "Reply" : "Send");
  send.addEventListener("click", () => {
    const t = text.value.trim();
    if (!t) return text.focus();
    if (p.kind === "question") answer({ decision: "allow", response: t });
    else answer({ decision: "deny", message: t });
  });
  other.append(text, send);
  card.append(other);
  return card;
}

function questionBlock(q: Question, qi: number, requestId: string): { node: HTMLElement; question: string; value: () => string | string[] | null } {
  const node = el("fieldset", "question");
  const legend = el("legend");
  if (q.header) legend.append(el("span", "chip", q.header), " ");
  legend.append(q.question);
  node.append(legend);
  const name = `q-${requestId}-${qi}`;
  const inputs: HTMLInputElement[] = [];
  q.options.forEach((o) => {
    const label = el("label", "option");
    const i = el("input");
    i.type = q.multiSelect ? "checkbox" : "radio";
    i.name = name;
    i.value = o.label;
    inputs.push(i);
    label.append(i, el("span", "option-label", o.label));
    if (o.description) label.append(el("span", "option-desc", o.description));
    node.append(label);
  });
  // "Other": the user's own text becomes the answer (not the word "Other").
  const otherLabel = el("label", "option");
  const otherBox = el("input");
  otherBox.type = q.multiSelect ? "checkbox" : "radio";
  otherBox.name = name;
  const otherText = el("input", "other-text");
  otherText.type = "text";
  otherText.placeholder = "Other…";
  // Clicking or typing in "Other…" selects it (v0.18.0 test: the radio stayed empty); clearing the text unselects it.
  otherText.addEventListener("focus", () => (otherBox.checked = true));
  otherText.addEventListener("input", () => (otherBox.checked = otherText.value.trim() !== "" || document.activeElement === otherText));
  otherLabel.append(otherBox, otherText);
  node.append(otherLabel);

  const value = (): string | string[] | null => {
    const picked = inputs.filter((i) => i.checked).map((i) => i.value);
    const own = otherBox.checked ? otherText.value.trim() : "";
    if (own) picked.push(own);
    if (picked.length === 0) return null;
    return q.multiSelect ? picked : picked[0];
  };
  return { node, question: q.question, value };
}

/** Kotlin answered (or the CLI cancelled) the request: freeze the card and say what happened. */
export function resolveCard(requestId: string, outcome: string): void {
  const card = cards.get(requestId);
  if (!card) return;
  card.classList.add("resolved");
  card.querySelectorAll("button, input, select, textarea").forEach((e) => ((e as HTMLButtonElement).disabled = true));
  card.querySelector(".permission-outcome")?.remove();
  card.append(el("div", "permission-outcome", outcome));
}

export function clearCards(): void {
  cards.clear();
}

// ---------- mode menu ----------

export type Mode = { id: string; label: string };

export function modeControl(): { node: HTMLElement; setModes: (m: Mode[]) => void; setMode: (id: string) => void } {
  const node = el("span", "mode");
  const button = el("button", "link-button mode-button", "Ask before edits");
  button.title = "Permission mode";
  const menu = el("div", "mode-menu");
  menu.hidden = true;
  node.append(button, menu);
  let modes: Mode[] = [];
  let current = "default";

  const render = () => {
    button.textContent = `${modes.find((m) => m.id === current)?.label ?? current} ▾`;
    button.classList.toggle("danger", current === "bypassPermissions");
    menu.replaceChildren(
      ...modes.map((m) => {
        const item = el("button", `mode-item${m.id === current ? " active" : ""}`, m.label);
        item.addEventListener("click", () => {
          menu.hidden = true;
          if (m.id !== current) post({ type: "setMode", mode: m.id });
        });
        return item;
      }),
    );
  };
  button.addEventListener("click", () => {
    menu.hidden = !menu.hidden;
  });
  document.addEventListener("click", (ev) => {
    if (!node.contains(ev.target as Node)) menu.hidden = true;
  });
  return {
    node,
    setModes: (m) => {
      modes = m;
      render();
    },
    setMode: (id) => {
      current = id;
      render();
    },
  };
}
