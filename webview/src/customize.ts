// Phase 13a: the Customize view (in place of the transcript, like History): Commands, Skills, Output style, Instructions.
// The page only asks: Kotlin checks every skill name, style and file index against what the CLI listed. Text via textContent.
import { post } from "./bridge";

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

export type Command = { name: string; description: string; argumentHint: string; builtin: boolean; aliases: string[] };
type Skill = { name: string; displayName: string; description: string; source: string; tokens: number; state: string; lockedBy?: string };
type Instr = { label: string; path: string; hint: string; exists: boolean };
export type CustomizeData = {
  commands: Command[];
  skills: Skill[];
  skillsError?: string;
  outputStyle: { current?: string; available: string[] };
  instructions: Instr[];
};
export type Tab = "commands" | "skills" | "style" | "instructions";

const TABS: [Tab, string][] = [
  ["commands", "Commands"],
  ["skills", "Skills"],
  ["style", "Output style"],
  ["instructions", "Instructions"],
];
const STATES: [string, string][] = [
  ["on", "On"],
  ["name-only", "Name only (Claude sees just the name)"],
  ["user-invocable-only", "Only when you type it"],
  ["off", "Off"],
];
const LOCKS: Record<string, string> = {
  author: "its own file turns model use off",
  plugin: "managed with its plugin",
  policy: "set by your organization",
  flag: "set for this session",
  "reserved-name": "its name can't be stored in settings",
};

export function createCustomize(opts: { onClose: () => void; insertCommand: (text: string) => void }) {
  const view = el("div", "history customize");
  view.hidden = true;
  const bar = el("div", "history-bar");
  const search = el("input", "history-search");
  search.type = "search";
  search.placeholder = "Filter…";
  const close = el("button", "link-button", "Close");
  close.addEventListener("click", () => opts.onClose());
  bar.append(el("span", "info-title", "Customize"), search, close);
  const tabs = el("div", "history-filters");
  const body = el("div", "history-list customize-body");
  view.append(bar, tabs, body);

  let tab: Tab = "commands";
  let data: CustomizeData | null = null;

  const matches = (...texts: string[]) => {
    const q = search.value.trim().toLowerCase();
    return !q || texts.some((t) => t.toLowerCase().includes(q));
  };

  function render(): void {
    tabs.replaceChildren(
      ...TABS.map(([id, label]) => {
        const b = el("button", `filter-chip${id === tab ? " active" : ""}`, label);
        b.addEventListener("click", () => {
          tab = id;
          render();
        });
        return b;
      }),
    );
    search.hidden = tab === "style" || tab === "instructions";
    if (!data) {
      body.replaceChildren(el("div", "muted customize-empty", "Loading…"));
      return;
    }
    body.replaceChildren(...(tab === "commands" ? commands(data) : tab === "skills" ? skills(data) : tab === "style" ? style(data) : instructions(data)));
  }

  function commands(d: CustomizeData): HTMLElement[] {
    const rows = d.commands.filter((c) => matches(c.name, c.description, ...c.aliases));
    if (!rows.length) return [el("div", "muted customize-empty", "No commands match.")];
    return rows.map((c) => {
      const b = el("button", "history-open customize-row");
      const head = el("span", "customize-head");
      head.append(el("span", "customize-name", `/${c.name}`));
      if (c.argumentHint) head.append(el("span", "muted", c.argumentHint));
      head.append(el("span", "chip", c.builtin ? "built-in" : "custom"));
      b.append(head);
      if (c.description) b.append(el("span", "history-meta", c.description));
      if (c.aliases.length) b.append(el("span", "history-meta", `Also: ${c.aliases.map((a) => `/${a}`).join(", ")}`));
      b.title = "Put this command in the message box";
      b.addEventListener("click", () => opts.insertCommand(`/${c.name} `));
      return b;
    });
  }

  function skills(d: CustomizeData): HTMLElement[] {
    const out: HTMLElement[] = [];
    if (d.skillsError) out.push(el("div", "agent-error customize-empty", `Couldn't read skills: ${d.skillsError}`));
    const rows = d.skills.filter((s) => matches(s.displayName, s.description, s.source));
    if (!rows.length && !d.skillsError) out.push(el("div", "muted customize-empty", "No skills match."));
    for (const s of rows) {
      const row = el("div", "customize-row customize-skill");
      const text = el("div", "customize-text");
      const head = el("span", "customize-head");
      head.append(el("span", "customize-name", s.displayName), el("span", "chip", s.source));
      if (s.tokens) head.append(el("span", "muted", `~${s.tokens} tokens`));
      text.append(head);
      if (s.description) text.append(el("span", "history-meta", s.description));
      const select = el("select", "destination");
      for (const [id, label] of STATES) {
        const o = el("option", undefined, label);
        o.value = id;
        o.selected = id === s.state;
        select.append(o);
      }
      if (s.lockedBy) {
        select.disabled = true;
        select.title = `Can't change here: ${LOCKS[s.lockedBy] ?? s.lockedBy}`;
      }
      select.addEventListener("change", () => {
        select.disabled = true;
        post({ type: "skillState", name: s.name, state: select.value });
      });
      row.append(text, select);
      out.push(row);
    }
    out.push(el("div", "muted info-foot customize-empty", "Saved in .claude/settings.local.json (this project, just you), as the terminal's /skills does."));
    return out;
  }

  function style(d: CustomizeData): HTMLElement[] {
    const out: HTMLElement[] = [];
    const name = "output-style";
    for (const s of d.outputStyle.available) {
      const label = el("label", "option customize-row");
      const radio = el("input");
      radio.type = "radio";
      radio.name = name;
      radio.checked = s === d.outputStyle.current;
      radio.addEventListener("change", () => post({ type: "outputStyle", style: s }));
      label.append(radio, el("span", "option-label", s));
      out.push(label);
    }
    if (!d.outputStyle.available.length) out.push(el("div", "muted customize-empty", "Claude Code didn't list any output styles."));
    const custom = el("button", "link-button customize-row", "Build a custom style…");
    custom.title = "Starts a message asking Claude to write a style file in .claude/output-styles/";
    custom.addEventListener("click", () => {
      post({ type: "customStyle" });
      opts.onClose();
    });
    out.push(custom);
    return out;
  }

  function instructions(d: CustomizeData): HTMLElement[] {
    return d.instructions.map((f, i) => {
      const row = el("div", "customize-row customize-skill");
      const text = el("div", "customize-text");
      text.append(el("span", "customize-name", f.label), el("span", "history-meta", f.path), el("span", "history-meta", f.hint));
      const b = el("button", "link-button", f.exists ? "Open" : "Create…");
      b.addEventListener("click", () => post({ type: "openInstructions", index: i }));
      row.append(text, b);
      return row;
    });
  }

  search.addEventListener("input", render);

  return {
    view,
    open(which?: Tab): void {
      if (which) tab = which;
      view.hidden = false;
      data = null;
      search.value = "";
      render();
      post({ type: "customize" });
    },
    show(d: CustomizeData): void {
      data = d;
      if (!view.hidden) render();
    },
    styleChanged(style: string | null): void {
      if (data && style) data.outputStyle.current = style;
      if (!view.hidden) render();
    },
  };
}
