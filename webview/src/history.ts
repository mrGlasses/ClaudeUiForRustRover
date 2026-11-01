// Phase 10: the conversation history. 10a: list, search, resume. 10c: filters (Active / Open / Archived / All), rename,
// archive / unarchive (one or all shown), groups (create, rename, delete, move a conversation). Kotlin checks every action
// against this project's sessions; the page only asks. All text goes in with textContent.
import { post } from "./bridge";

export type HistoryItem = {
  id: string;
  title: string;
  modified: number;
  prompts: number;
  current: boolean;
  open: boolean;
  archived: boolean;
  autoArchived: boolean;
  group?: string;
};
type Filter = "ACTIVE" | "OPEN" | "ARCHIVED" | "ALL";

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function ago(ms: number): string {
  const s = (Date.now() - ms) / 1000;
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  return s < 7 * 86400 ? `${Math.floor(s / 86400)} d ago` : new Date(ms).toLocaleDateString();
}

export function createHistory(onClose: () => void): {
  view: HTMLElement;
  open: () => void;
  render: (items: HistoryItem[], groups: string[], filter: string) => void;
} {
  const view = el("div", "history");
  view.hidden = true;
  const search = el("input", "history-search");
  search.type = "search";
  search.placeholder = "Search conversations…";
  const close = el("button", "link-button", "Close");
  const bar = el("div", "history-bar");
  bar.append(search, close);

  let filter: Filter = "ACTIVE";
  const filters = el("div", "history-filters");
  const filterButtons = (["ACTIVE", "OPEN", "ARCHIVED", "ALL"] as Filter[]).map((f) => {
    const b = el("button", "filter-chip", { ACTIVE: "Active", OPEN: "Open", ARCHIVED: "Archived", ALL: "All" }[f]);
    b.addEventListener("click", () => {
      filter = f;
      request();
    });
    return b;
  });
  const newGroup = el("button", "link-button", "+ Group");
  newGroup.title = "Create a group";
  const bulk = el("button", "link-button");
  filters.append(...filterButtons, el("span", "spacer"), newGroup, bulk);

  const list = el("div", "history-list");
  const menu = el("div", "history-menu");
  menu.hidden = true;
  view.append(bar, filters, list, menu);

  let shown: HistoryItem[] = [];
  let groups: string[] = [];
  let timer = 0;

  const request = () => post({ type: "historyList", query: search.value, filter } as never);
  close.addEventListener("click", onClose);
  search.addEventListener("input", () => {
    clearTimeout(timer);
    timer = window.setTimeout(request, 250);
  });
  search.addEventListener("keydown", (ev) => {
    if (ev.key === "Escape") {
      ev.stopPropagation();
      onClose();
    }
  });
  document.addEventListener("click", (ev) => {
    if (!menu.contains(ev.target as Node) && !(ev.target as Element)?.closest?.(".more")) menu.hidden = true;
  });

  newGroup.addEventListener("click", () => askName("New group name", "", (name) => post({ type: "groupAction", action: "create", name } as never)));
  bulk.addEventListener("click", () => {
    const ids = shown.filter((i) => (filter === "ARCHIVED" ? i.archived : !i.archived && !i.open)).map((i) => i.id);
    if (ids.length) post({ type: "sessionAction", action: filter === "ARCHIVED" ? "unarchive" : "archive", ids } as never);
  });

  /** A one-line name input at the top of the list (Enter saves, Esc cancels). */
  function askName(label: string, value: string, done: (name: string) => void): void {
    const row = el("div", "name-row");
    const inputEl = el("input", "history-search");
    inputEl.value = value;
    inputEl.placeholder = label;
    inputEl.maxLength = 100;
    const ok = el("button", "link-button", "Save");
    const cancel = el("button", "link-button", "Cancel");
    const finish = (save: boolean) => {
      row.remove();
      const v = inputEl.value.trim();
      if (save && v) done(v);
    };
    ok.addEventListener("click", () => finish(true));
    cancel.addEventListener("click", () => finish(false));
    inputEl.addEventListener("keydown", (ev) => {
      if (ev.key === "Enter") finish(true);
      if (ev.key === "Escape") {
        ev.stopPropagation();
        finish(false);
      }
    });
    row.append(inputEl, ok, cancel);
    list.prepend(row);
    inputEl.focus();
    inputEl.select();
  }

  function showMenu(anchor: HTMLElement, entries: [string, () => void][]): void {
    menu.replaceChildren(
      ...entries.map(([label, run]) => {
        const b = el("button", "menu-item", label);
        b.addEventListener("click", () => {
          menu.hidden = true;
          run();
        });
        return b;
      }),
    );
    const r = anchor.getBoundingClientRect();
    const v = view.getBoundingClientRect();
    menu.style.top = `${r.bottom - v.top}px`;
    menu.style.right = "12px";
    menu.hidden = false;
  }

  function itemMenu(anchor: HTMLElement, it: HistoryItem): void {
    const act = (action: string, value?: string) => post({ type: "sessionAction", action, sessionId: it.id, value } as never);
    const entries: [string, () => void][] = [
      ["Rename…", () => askName("Conversation name", it.title, (v) => act("rename", v))],
      [it.archived ? "Unarchive" : "Archive", () => act(it.archived ? "unarchive" : "archive")],
    ];
    groups.filter((g) => g !== it.group).forEach((g) => entries.push([`Move to "${g}"`, () => act("setGroup", g)]));
    if (it.group) entries.push(["Remove from group", () => act("setGroup", "")]);
    showMenu(anchor, entries);
  }

  function groupMenu(anchor: HTMLElement, name: string): void {
    showMenu(anchor, [
      ["Rename group…", () => askName("Group name", name, (v) => post({ type: "groupAction", action: "rename", name, newName: v } as never))],
      ["Delete group (keeps its conversations)", () => post({ type: "groupAction", action: "delete", name } as never)],
    ]);
  }

  function row(it: HistoryItem): HTMLElement {
    const r = el("div", `history-item${it.current ? " current" : ""}`);
    const main = el("button", "history-open");
    const meta = [ago(it.modified), `${it.prompts} message${it.prompts === 1 ? "" : "s"}`];
    if (it.open) meta.push("open");
    if (it.autoArchived) meta.push("auto-archived");
    main.append(el("span", "history-title", it.title), el("span", "history-meta", meta.join(" · ")));
    main.addEventListener("click", () => {
      onClose();
      post({ type: "resume", sessionId: it.id });
    });
    const more = el("button", "more", "•••");
    more.title = "Rename, archive, group";
    more.addEventListener("click", (ev) => {
      ev.stopPropagation();
      itemMenu(more, it);
    });
    // Right-click anywhere on the row opens the same menu.
    r.addEventListener("contextmenu", (ev) => {
      ev.preventDefault();
      itemMenu(more, it);
    });
    r.append(main, more);
    return r;
  }

  function render(items: HistoryItem[], g: string[], f: string): void {
    shown = items;
    groups = g;
    filter = (f as Filter) || filter;
    filterButtons.forEach((b, i) => b.classList.toggle("active", (["ACTIVE", "OPEN", "ARCHIVED", "ALL"] as Filter[])[i] === filter));
    bulk.textContent = filter === "ARCHIVED" ? "Unarchive all" : "Archive all shown";
    bulk.hidden = filter === "OPEN" || items.length === 0;
    const nodes: HTMLElement[] = [];
    for (const name of groups) {
      const inGroup = items.filter((i) => i.group === name);
      const head = el("div", "group-head");
      const more = el("button", "more", "•••");
      more.title = "Rename or delete the group";
      more.addEventListener("click", (ev) => {
        ev.stopPropagation();
        groupMenu(more, name);
      });
      head.append(el("span", undefined, `${name} (${inGroup.length})`), more);
      nodes.push(head, ...inGroup.map(row));
    }
    const rest = items.filter((i) => !i.group);
    if (groups.length && rest.length) nodes.push(el("div", "group-head", "Other conversations"));
    nodes.push(...rest.map(row));
    if (items.length === 0)
      nodes.push(el("div", "notice", search.value ? "No conversation matches." : filter === "ARCHIVED" ? "Nothing archived." : "No conversations here yet."));
    list.replaceChildren(...nodes);
  }

  return {
    view,
    open: () => {
      view.hidden = false;
      list.replaceChildren(el("div", "notice", "Loading…"));
      request();
      search.focus();
    },
    render,
  };
}
