// Phase 11b: the agent map. Subagents, background commands and monitors as a tree with status, time, tokens and tool count;
// a subagent's transcript, a command's live output, and Stop. The page only asks (taskAction); Kotlin checks every id.
// Everything is shown as plain text except a subagent's own replies (sanitized Markdown, rule 1).
import { post } from "./bridge";
import { renderMarkdown } from "./markdown";

type Task = {
  id: string;
  toolUseId?: string;
  description: string;
  kind: string;
  subagentType?: string;
  parentId?: string;
  background: boolean;
  status: string;
  started: number;
  ended?: number;
  durationMs?: number;
  tokens?: number;
  toolUses?: number;
  lastTool?: string;
  summary?: string;
  error?: string;
  prompt?: string;
  metaEl?: HTMLElement;
  stopping?: boolean;
  open?: "transcript" | "output";
};

type Activity = { kind: string; text: string };

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

const ACTIVITY_MAX = 300;
const RUNNING = new Set(["running", "pending", "paused"]);
const MARK: Record<string, string> = { running: "●", pending: "○", paused: "Ⅱ", completed: "✓", failed: "✗", killed: "■", stopped: "■" };

export function createAgentMap() {
  const box = el("details", "agents");
  box.open = true;
  box.hidden = true;
  const summary = el("summary");
  const label = el("span", "agents-label", "Agents and tasks");
  const hide = el("button", "link-button todo-hide", "Hide");
  hide.title = "Hide (type /tasks to show it again)";
  hide.addEventListener("click", (ev) => {
    ev.preventDefault();
    box.hidden = true;
  });
  summary.append(label, hide);
  const list = el("div", "agents-list");
  box.append(summary, list);

  const tasks = new Map<string, Task>();
  const activity = new Map<string, Activity[]>(); // by the Agent tool call's id
  let timer: number | undefined;
  let poller: number | undefined;

  const running = () => [...tasks.values()].filter((t) => RUNNING.has(t.status));

  function meta(t: Task): string {
    const parts: string[] = [];
    const ms = t.durationMs ?? ((t.ended ?? Date.now()) - t.started);
    if (ms > 0) parts.push(ms < 60_000 ? `${Math.round(ms / 1000)} s` : `${Math.floor(ms / 60_000)} min ${Math.round((ms % 60_000) / 1000)} s`);
    if (t.tokens) parts.push(t.tokens >= 1000 ? `${(t.tokens / 1000).toFixed(1)}k tokens` : `${t.tokens} tokens`);
    if (t.toolUses) parts.push(`${t.toolUses} tool${t.toolUses === 1 ? "" : "s"}`);
    if (t.lastTool && RUNNING.has(t.status)) parts.push(`now: ${t.lastTool}`);
    return parts.join(" · ");
  }

  function kindLabel(t: Task): string {
    if (t.kind === "agent") return t.subagentType || "Agent";
    if (t.kind === "shell") return "Command";
    if (t.kind === "monitor") return "Monitor";
    return "Task";
  }

  function render(): void {
    const all = [...tasks.values()];
    const live = running().length;
    label.textContent = live ? `Agents and tasks · ${live} running` : `Agents and tasks · ${all.length} done`;
    // Tree: children under their parent, in start order.
    const children = new Map<string | undefined, Task[]>();
    for (const t of all) {
      const parent = t.parentId && tasks.has(t.parentId) ? t.parentId : undefined;
      if (!children.has(parent)) children.set(parent, []);
      children.get(parent)!.push(t);
    }
    const rows: HTMLElement[] = [];
    const walk = (parent: string | undefined, depth: number) => {
      for (const t of children.get(parent) ?? []) {
        rows.push(row(t, depth));
        walk(t.id, depth + 1);
      }
    };
    walk(undefined, 0);
    list.replaceChildren(...rows);
    // The clock ticks on the time only, so open transcripts and outputs aren't rebuilt every second.
    if (live && timer === undefined) timer = window.setInterval(() => running().forEach((t) => t.metaEl && (t.metaEl.textContent = meta(t))), 1000);
    if (!live && timer !== undefined) {
      window.clearInterval(timer);
      timer = undefined;
    }
  }

  function row(t: Task, depth: number): HTMLElement {
    const r = el("div", `agent ${t.status}`);
    r.style.marginLeft = `${depth * 16}px`;
    const head = el("div", "agent-head");
    head.append(
      el("span", "agent-mark", MARK[t.status] ?? "○"),
      el("span", "agent-kind", kindLabel(t)),
      el("span", "agent-desc", t.description),
    );
    if (t.background) head.append(el("span", "chip agent-bg", "background"));
    t.metaEl = el("span", "agent-meta", meta(t));
    head.append(t.metaEl);
    const actions = el("span", "agent-actions");
    if (t.kind === "agent") actions.append(toggle(t, "transcript", "Transcript"));
    if (t.kind === "shell" || t.kind === "monitor") actions.append(toggle(t, "output", "Output"));
    if (RUNNING.has(t.status)) {
      const stop = el("button", "link-button agent-stop", t.stopping ? "Stopping…" : "Stop");
      stop.disabled = t.stopping === true;
      stop.addEventListener("click", () => {
        t.stopping = true;
        stop.disabled = true;
        stop.textContent = "Stopping…";
        post({ type: "taskAction", action: "stop", id: t.id });
      });
      actions.append(stop);
    }
    head.append(actions);
    r.append(head);
    if (t.error) r.append(el("div", "agent-error", t.error));
    if (!RUNNING.has(t.status) && t.summary && t.open !== "transcript") r.append(el("div", "agent-summary", t.summary));
    if (t.open === "transcript") r.append(transcriptOf(t));
    if (t.open === "output") r.append(outputBox(t));
    return r;
  }

  function toggle(t: Task, what: "transcript" | "output", text: string): HTMLElement {
    const b = el("button", "link-button", t.open === what ? `Hide ${text.toLowerCase()}` : text);
    b.addEventListener("click", () => {
      t.open = t.open === what ? undefined : what;
      if (t.open === "output") requestOutput(t);
      render();
    });
    return b;
  }

  function transcriptOf(t: Task): HTMLElement {
    const box = el("div", "agent-transcript");
    if (t.prompt) {
      box.append(el("div", "agent-label", "Task given to the agent"), el("div", "agent-prompt", t.prompt));
    }
    const items = (t.toolUseId && activity.get(t.toolUseId)) || [];
    if (!items.length) box.append(el("div", "muted", RUNNING.has(t.status) ? "Working… nothing to show yet." : "No messages."));
    for (const a of items) {
      if (a.kind === "tool") box.append(el("div", "agent-tool", a.text));
      else {
        const m = el("div", "agent-text markdown");
        m.innerHTML = renderMarkdown(a.text); // sanitized
        box.append(m);
      }
    }
    if (!RUNNING.has(t.status) && t.summary) box.append(el("div", "agent-label", "Result"), el("div", "agent-summary", t.summary));
    return box;
  }

  const outputs = new Map<string, { text: string; truncated: boolean; error?: string }>();
  function outputBox(t: Task): HTMLElement {
    const o = outputs.get(t.id);
    const box = el("div", "agent-output");
    if (o?.error) box.append(el("div", "agent-error", o.error));
    if (o?.truncated) box.append(el("div", "muted", "Showing the last 8 KB."));
    const pre = el("pre", undefined, o ? o.text || "(no output yet)" : "Loading…");
    box.append(pre);
    requestAnimationFrame(() => (pre.scrollTop = pre.scrollHeight));
    return box;
  }

  function requestOutput(t: Task): void {
    post({ type: "taskAction", action: "output", id: t.id });
  }

  // Live output: poll the open ones every 2 s while they run (and once more when they end).
  function updatePoller(): void {
    const want = [...tasks.values()].some((t) => t.open === "output" && RUNNING.has(t.status));
    if (want && poller === undefined) {
      poller = window.setInterval(() => {
        for (const t of tasks.values()) if (t.open === "output" && RUNNING.has(t.status)) requestOutput(t);
      }, 2000);
    } else if (!want && poller !== undefined) {
      window.clearInterval(poller);
      poller = undefined;
    }
  }

  function show(): void {
    box.hidden = false;
    box.open = true;
  }

  function upsert(id: string, fill: (t: Task) => void): Task {
    let t = tasks.get(id);
    if (!t) {
      t = { id, description: "", kind: "other", background: false, status: "running", started: Date.now() };
      tasks.set(id, t);
    }
    fill(t);
    return t;
  }

  return {
    node: box,
    /** Handles the agent-map messages; false for anything else. */
    handle(msg: { type: string } & Record<string, unknown>): boolean {
      const s = (k: string) => (typeof msg[k] === "string" ? (msg[k] as string) : undefined);
      const n = (k: string) => (typeof msg[k] === "number" ? (msg[k] as number) : undefined);
      switch (msg.type) {
        case "agentTaskStarted": {
          if (msg.hidden === true) return true;
          upsert(s("taskId")!, (t) => {
            t.toolUseId = s("toolUseId");
            t.description = s("description") ?? "";
            t.kind = s("kind") ?? "other";
            t.subagentType = s("subagentType");
            t.parentId = s("parentTaskId");
            t.background = msg.background === true;
            t.prompt = s("prompt");
            t.status = "running";
          });
          if (tasks.size === 1) show(); // the first one opens the map
          break;
        }
        case "agentTaskProgress": {
          const t = tasks.get(s("taskId")!);
          if (!t) return true;
          t.tokens = n("tokens") ?? t.tokens;
          t.toolUses = n("toolUses") ?? t.toolUses;
          t.lastTool = s("lastTool") ?? t.lastTool;
          if (s("summary")) t.summary = s("summary");
          break;
        }
        case "agentTaskUpdated": {
          const t = tasks.get(s("taskId")!);
          if (!t) return true;
          if (s("status")) t.status = s("status")!;
          if (s("description")) t.description = s("description")!;
          if (s("error")) t.error = s("error");
          if (typeof msg.background === "boolean") t.background = msg.background;
          if (!RUNNING.has(t.status)) t.ended ??= Date.now();
          break;
        }
        case "agentTaskEnded": {
          const t = tasks.get(s("taskId")!);
          if (!t) return true;
          t.status = s("status") ?? "completed";
          t.summary = s("summary") || t.summary;
          t.tokens = n("tokens") ?? t.tokens;
          t.toolUses = n("toolUses") ?? t.toolUses;
          t.durationMs = n("durationMs") ?? t.durationMs;
          t.ended ??= Date.now();
          if (t.open === "output") requestOutput(t); // the last of it
          break;
        }
        case "agentTasksLive": {
          for (const o of (msg.tasks as Record<string, unknown>[]) ?? []) {
            if (o.hidden === true || typeof o.taskId !== "string") continue;
            upsert(o.taskId, (t) => {
              t.background = true;
              if (!t.description) t.description = typeof o.description === "string" ? o.description : "";
              if (t.kind === "other" && typeof o.kind === "string") t.kind = o.kind;
              if (!t.subagentType && typeof o.subagentType === "string") t.subagentType = o.subagentType;
              if (!t.parentId && typeof o.parentTaskId === "string") t.parentId = o.parentTaskId;
            });
          }
          if (tasks.size && box.hidden && running().length) show();
          break;
        }
        case "agentTasksGone":
          for (const t of tasks.values()) if (RUNNING.has(t.status)) {
            t.status = "stopped";
            t.ended ??= Date.now();
          }
          break;
        case "agentActivity": {
          const id = s("toolUseId")!;
          const items = activity.get(id) ?? [];
          items.push({ kind: s("kind") ?? "text", text: s("text") ?? "" });
          if (items.length > ACTIVITY_MAX) items.splice(0, items.length - ACTIVITY_MAX);
          activity.set(id, items);
          const open = [...tasks.values()].some((t) => t.toolUseId === id && t.open === "transcript");
          if (!open) return true; // nothing visible changed
          break;
        }
        case "agentTaskOutput": {
          outputs.set(s("taskId")!, { text: s("output") ?? "", truncated: msg.truncated === true, error: s("error") });
          break;
        }
        default:
          return false;
      }
      render();
      updatePoller();
      return true;
    },
    /** `/tasks`: show the map (false when there's nothing in it yet). */
    show(): boolean {
      if (!tasks.size) return false;
      show();
      render();
      return true;
    },
    clear(): void {
      tasks.clear();
      activity.clear();
      outputs.clear();
      list.replaceChildren();
      box.hidden = true;
      if (timer !== undefined) window.clearInterval(timer);
      if (poller !== undefined) window.clearInterval(poller);
      timer = poller = undefined;
    },
  };
}
