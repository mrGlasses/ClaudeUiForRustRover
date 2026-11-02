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
export type Tab = "commands" | "skills" | "style" | "instructions" | "hooks" | "permissions" | "memory";

const TABS: [Tab, string][] = [
  ["commands", "Commands"],
  ["skills", "Skills"],
  ["style", "Output style"],
  ["instructions", "Instructions"],
  ["hooks", "Hooks"],
  ["permissions", "Permissions"],
  ["memory", "Memory"],
];

// Phase 13b data (each tab asks for its own when shown).
type HookRow = { event: string; matcher: string; source: string; sourceLabel: string; type: string; text: string; contentLabel: string; timeout?: number; disabled: boolean; editable: boolean; formEditable: boolean };
export type HooksData = { error?: string; events: { name: string; summary: string; supportsMatcher: boolean }[]; hooks: HookRow[]; locked: boolean; lockReason?: string; notice?: string };
type RuleRow = { behavior: string; rule: string; source: string; description?: string; removable: boolean; notInEffect: boolean };
export type PermissionsData = { error?: string; rules: RuleRow[]; directories: { path: string; source: string }[]; managedOnly: boolean; windows?: boolean };
type Toggle = { enabled: boolean; toggleable: boolean; status: string; detail?: string };
export type MemoryData = {
  error?: string;
  files: { label: string; description: string; path: string; exists: boolean }[];
  folders: { label: string; description: string; path: string }[];
  memories: { name: string; description: string; kind?: string; modified?: number }[];
  autoMemory?: Toggle;
  autoDream?: Toggle;
};
const DESTS: [string, string][] = [
  ["localSettings", "This project, just you"],
  ["projectSettings", "This project, shared"],
  ["userSettings", "All your projects"],
];
const BEHAVIOR_LABEL: Record<string, string> = { allow: "Allow", ask: "Ask", deny: "Deny" };
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
  let hooksData: HooksData | null = null;
  let permData: PermissionsData | null = null;
  let memData: MemoryData | null = null;
  const LIVE: Tab[] = ["hooks", "permissions", "memory"];
  const request = (t: Tab) => {
    if (t === "hooks") hooksData = null;
    if (t === "permissions") permData = null;
    if (t === "memory") memData = null;
    post({ type: "customizeTab", tab: t } as never);
  };

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
          if (LIVE.includes(id)) request(id);
          render();
        });
        return b;
      }),
    );
    search.hidden = tab !== "commands" && tab !== "skills" && tab !== "permissions";
    if (tab === "hooks") return void body.replaceChildren(...(hooksData ? hooks(hooksData) : [loading()]));
    if (tab === "permissions") return void body.replaceChildren(...(permData ? permissions(permData) : [loading()]));
    if (tab === "memory") return void body.replaceChildren(...(memData ? memory(memData) : [loading()]));
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

  const loading = () => el("div", "muted customize-empty", "Loading…");

  // ---------- hooks ----------
  function hooks(d: HooksData): HTMLElement[] {
    const out: HTMLElement[] = [];
    if (d.error) out.push(el("div", "agent-error customize-empty", `Couldn't read hooks: ${d.error}`));
    if (d.lockReason) out.push(el("div", "notice customize-empty", d.lockReason));
    if (d.notice) out.push(el("div", "notice customize-empty", d.notice));
    if (!d.locked) {
      const add = el("button", "link-button customize-row", "+ Add a hook");
      add.addEventListener("click", () => add.replaceWith(hookForm(d, null)));
      out.push(add);
    }
    if (!d.hooks.length) out.push(el("div", "muted customize-empty", "No hooks configured."));
    d.hooks.forEach((h, i) => {
      const row = el("div", "customize-row customize-skill");
      const text = el("div", "customize-text");
      const head = el("span", "customize-head");
      head.append(el("span", "customize-name", h.event));
      if (h.matcher) head.append(el("span", "chip", h.matcher));
      head.append(el("span", "muted", h.type));
      if (h.disabled) head.append(el("span", "chip", "not running"));
      text.append(head, el("span", "history-meta mono", `${h.contentLabel || "Runs"}: ${h.text}`), el("span", "history-meta", h.sourceLabel));
      row.append(text);
      if (!d.locked && h.formEditable) {
        const edit = el("button", "link-button", "Edit");
        edit.addEventListener("click", () => row.replaceWith(hookForm(d, i)));
        row.append(edit);
      }
      if (!d.locked && h.editable) {
        const del = el("button", "link-button deny", "Remove");
        del.addEventListener("click", () => post({ type: "hookEdit", op: "remove", index: i } as never));
        row.append(del);
      } else if (!h.editable) row.title = "Read-only here (a plugin, your organization or this session set it)";
      out.push(row);
    });
    return out;
  }

  function hookForm(d: HooksData, index: number | null): HTMLElement {
    const h = index === null ? null : d.hooks[index];
    const form = el("div", "customize-row hook-form");
    const event = el("select", "destination");
    for (const e of d.events) {
      const o = el("option", undefined, e.name);
      o.value = e.name;
      o.selected = h ? e.name === h.event : e.name === "PostToolUse";
      o.title = e.summary;
      event.append(o);
    }
    const summary = el("div", "history-meta");
    const matcher = el("input", "history-search");
    matcher.placeholder = "Matcher: tool name or pattern, e.g. Edit|Write (empty: every tool)";
    matcher.value = h?.matcher ?? "";
    const command = el("textarea", "input");
    command.rows = 2;
    command.placeholder = "Command to run, e.g. cargo fmt";
    command.value = h?.text ?? "";
    const timeout = el("input", "history-search hook-timeout");
    timeout.type = "number";
    timeout.min = "1";
    timeout.max = "3600";
    timeout.placeholder = "Timeout (s)";
    if (h?.timeout) timeout.value = String(h.timeout);
    const source = el("select", "destination");
    for (const [id, label] of DESTS) {
      const o = el("option", undefined, label);
      o.value = id;
      source.append(o);
    }
    const sync = () => {
      const e = d.events.find((x) => x.name === event.value);
      summary.textContent = e?.summary ?? "";
      matcher.hidden = !e?.supportsMatcher;
    };
    event.addEventListener("change", sync);
    sync();
    const save = el("button", "send", "Save");
    save.addEventListener("click", () => {
      if (!command.value.trim()) return command.focus();
      save.disabled = true;
      post({
        type: "hookEdit",
        op: index === null ? "add" : "replace",
        index: index ?? undefined,
        source: source.value,
        event: event.value,
        matcher: matcher.hidden ? "" : matcher.value,
        command: command.value,
        timeout: timeout.value ? Number(timeout.value) : undefined,
      } as never);
    });
    const cancel = el("button", "link-button", "Cancel");
    cancel.addEventListener("click", render);
    const row1 = el("div", "customize-head");
    row1.append(el("span", "muted", "When"), event);
    if (index === null) row1.append(el("span", "muted", "save in"), source);
    const row2 = el("div", "customize-head");
    row2.append(save, cancel, timeout);
    form.append(el("div", "info-title", index === null ? "New hook" : "Edit hook"), row1, summary, matcher, command, row2);
    return form;
  }

  // ---------- permissions ----------
  function permissions(d: PermissionsData): HTMLElement[] {
    const out: HTMLElement[] = [];
    if (d.error) out.push(el("div", "agent-error customize-empty", `Couldn't read the rules: ${d.error}`));
    if (d.managedOnly) out.push(el("div", "notice customize-empty", "Your organization allows only its own rules: the others are listed but not in effect."));
    out.push(ruleForm(d.windows === true));
    if (d.windows) out.push(el("div", "muted customize-empty", "On Windows Claude runs commands with its PowerShell tool: write command rules as PowerShell(cargo check:*). Bash(…) rules only match the Bash tool."));
    for (const behavior of ["allow", "ask", "deny"]) {
      const rows = d.rules.map((r, i) => ({ r, i })).filter(({ r }) => r.behavior === behavior && matches(r.rule, r.description ?? "", r.source));
      out.push(el("div", "info-section customize-empty", `${BEHAVIOR_LABEL[behavior]} (${rows.length})`));
      for (const { r, i } of rows) {
        const row = el("div", "customize-row customize-skill");
        const text = el("div", "customize-text");
        text.append(el("span", "customize-name", r.rule));
        if (r.description) text.append(el("span", "history-meta", r.description));
        text.append(el("span", "history-meta", r.notInEffect ? `${r.source} · not in effect` : r.source));
        row.append(text);
        if (r.removable) {
          const del = el("button", "link-button deny", "Remove");
          del.addEventListener("click", () => {
            del.disabled = true;
            post({ type: "permissionEdit", op: "remove", index: i } as never);
          });
          row.append(del);
        }
        out.push(row);
      }
    }
    if (d.directories.length) {
      out.push(el("div", "info-section customize-empty", "Extra folders Claude may use"));
      for (const dir of d.directories) out.push(el("div", "customize-row history-meta", `${dir.path} · ${dir.source}`));
    }
    return out;
  }

  function ruleForm(windows: boolean): HTMLElement {
    const form = el("div", "customize-row hook-form");
    const rules = el("textarea", "input");
    rules.rows = 2;
    rules.placeholder = `One rule per line, e.g. ${windows ? "PowerShell" : "Bash"}(cargo test:*)  Read(./src/**)  WebFetch(domain:docs.rs)`;
    const behavior = el("select", "destination");
    for (const b of ["allow", "ask", "deny"]) {
      const o = el("option", undefined, BEHAVIOR_LABEL[b]);
      o.value = b;
      behavior.append(o);
    }
    const dest = el("select", "destination");
    for (const [id, label] of DESTS) {
      const o = el("option", undefined, label);
      o.value = id;
      dest.append(o);
    }
    const add = el("button", "send", "Add");
    add.addEventListener("click", () => {
      if (!rules.value.trim()) return rules.focus();
      add.disabled = true;
      post({ type: "permissionEdit", op: "add", rules: rules.value, behavior: behavior.value, destination: dest.value } as never);
    });
    const row = el("div", "customize-head");
    row.append(behavior, el("span", "muted", "save in"), dest, add);
    form.append(rules, row);
    return form;
  }

  // ---------- memory ----------
  function memory(d: MemoryData): HTMLElement[] {
    const out: HTMLElement[] = [];
    if (d.error) out.push(el("div", "agent-error customize-empty", `Couldn't read memory: ${d.error}`));
    const toggle = (label: string, key: "autoMemory" | "autoDream", t?: Toggle) => {
      if (!t) return;
      const row = el("label", "model-toggle customize-row");
      const box = el("input");
      box.type = "checkbox";
      box.checked = t.enabled;
      box.disabled = !t.toggleable;
      box.addEventListener("change", () => {
        box.disabled = true;
        post({ type: "memoryAction", action: "toggle", key, on: box.checked } as never);
      });
      row.append(box, el("span", undefined, label), el("span", "muted", [t.status, t.detail].filter(Boolean).join(" · ")));
      out.push(row);
    };
    toggle("Auto-memory (Claude saves what it learns about you and this project)", "autoMemory", d.autoMemory);
    toggle("Auto-dream (tidies saved memories in the background)", "autoDream", d.autoDream);
    const open = (what: string, index: number, label: string, sub: string, button: string) => {
      const row = el("div", "customize-row customize-skill");
      const text = el("div", "customize-text");
      text.append(el("span", "customize-name", label));
      if (sub) text.append(el("span", "history-meta", sub));
      const b = el("button", "link-button", button);
      b.addEventListener("click", () => post({ type: "memoryAction", action: "open", what, index } as never));
      row.append(text, b);
      return row;
    };
    if (d.files.length) out.push(el("div", "info-section customize-empty", "Instruction files"));
    d.files.forEach((f, i) => out.push(open("file", i, f.label, [f.description, f.path].filter(Boolean).join(" · "), f.exists ? "Open" : "Create…")));
    if (d.folders.length) out.push(el("div", "info-section customize-empty", "Memory folders"));
    d.folders.forEach((f, i) => out.push(open("folder", i, f.label, [f.description, f.path].filter(Boolean).join(" · "), "Show folder")));
    if (d.memories.length) out.push(el("div", "info-section customize-empty", `Saved memories (${d.memories.length})`));
    d.memories.forEach((m, i) => {
      const when = m.modified ? new Date(m.modified).toLocaleDateString() : "";
      const row = open("memory", i, m.name, [m.kind, m.description, when].filter(Boolean).join(" · "), "Open");
      const del = el("button", "link-button deny", "Delete");
      del.addEventListener("click", () => post({ type: "memoryAction", action: "delete", what: "memory", index: i } as never));
      row.append(del);
      out.push(row);
    });
    return out;
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
      if (LIVE.includes(tab)) request(tab);
    },
    showTab(t: "hooksData" | "permissionsData" | "memoryData", d: unknown): void {
      if (t === "hooksData") hooksData = d as HooksData;
      if (t === "permissionsData") permData = d as PermissionsData;
      if (t === "memoryData") memData = d as MemoryData;
      if (!view.hidden) render();
    },
    /** A native dialog was cancelled or an edit was refused: re-enable the form. */
    done(): void {
      if (!view.hidden) render();
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
