// Phase 12b: `/usage` and `/status` cards, and the header's "•••" menu (Usage, Status, Report a problem, Sign out).
// Everything is plain text (textContent). Kotlin builds the data from the CLI's own answers and confirms Sign out natively.
import { post } from "./bridge";

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

type Limit = { label: string; utilization: number; resetsAt?: string };
type Period = {
  requests: number;
  sessions: number;
  behaviors: { label: string; pct: number; count: number }[];
  recommendations: { title: string; detail?: string; pct: number }[];
  canFix: boolean;
  attribution: { kind: string; name: string; pct: number }[];
};
export type UsageReport = {
  error?: string;
  plan?: string;
  limitsAvailable?: boolean;
  limits?: Limit[];
  extraUsage?: { utilization?: number; used?: number; limit?: number; currency?: string };
  session?: { costUsd: number; apiMs: number; wallMs: number; linesAdded: number; linesRemoved: number };
  periods?: { day?: Period; week?: Period };
};
export type StatusReport = { rows: { label: string; value: string }[]; mcp: { name: string; status: string }[] };

function resets(iso?: string): string {
  if (!iso) return "";
  const ms = new Date(iso).getTime() - Date.now();
  if (!Number.isFinite(ms)) return "";
  if (ms <= 0) return "resets now";
  const h = Math.floor(ms / 3_600_000);
  const m = Math.round((ms % 3_600_000) / 60_000);
  if (h >= 24) return `resets ${new Date(iso).toLocaleString([], { weekday: "short", hour: "2-digit", minute: "2-digit" })}`;
  return h ? `resets in ${h} h ${m} min` : `resets in ${m} min`;
}

function bar(label: string, pct: number, note: string): HTMLElement {
  const row = el("div", "usage-limit");
  const head = el("div", "usage-limit-head");
  head.append(el("span", undefined, label), el("span", "muted", `${Math.round(pct)}% used${note ? ` · ${note}` : ""}`));
  const track = el("div", "usage-track");
  const fill = el("div", `usage-fill${pct >= 90 ? " high" : pct >= 70 ? " mid" : ""}`);
  fill.style.width = `${Math.max(0, Math.min(100, pct))}%`;
  track.append(fill);
  row.append(head, track);
  return row;
}

const duration = (ms: number) => (ms >= 60_000 ? `${Math.floor(ms / 60_000)} min ${Math.round((ms % 60_000) / 1000)} s` : `${Math.round(ms / 1000)} s`);

export function usageCard(u: UsageReport): HTMLElement {
  const card = el("div", "info-card usage");
  card.append(el("div", "info-title", `Usage${u.plan ? ` · ${u.plan} plan` : ""}`));
  if (u.error) card.append(el("div", "agent-error", `Couldn't read usage: ${u.error}`));

  if (u.limits?.length) for (const l of u.limits) card.append(bar(l.label, l.utilization, resets(l.resetsAt)));
  else if (!u.error) card.append(el("div", "muted", u.limitsAvailable === false ? "Plan limits don't apply to this sign-in (API key or cloud provider)." : "No plan limits reported."));
  if (u.extraUsage?.utilization !== undefined) {
    const money = u.extraUsage.used !== undefined && u.extraUsage.limit !== undefined ? `${u.extraUsage.used} of ${u.extraUsage.limit} ${u.extraUsage.currency ?? ""}`.trim() : "";
    card.append(bar("Extra usage this month", u.extraUsage.utilization, money));
  }
  if (u.session) {
    const s = u.session;
    card.append(
      el("div", "info-section", "This session"),
      el("div", undefined, `$${s.costUsd.toFixed(4)} · ${duration(s.apiMs)} of API time (${duration(s.wallMs)} in all) · +${s.linesAdded} −${s.linesRemoved} lines`),
    );
  }

  const periods = u.periods ?? {};
  const keys = (["day", "week"] as const).filter((k) => periods[k]);
  if (keys.length) {
    card.append(el("div", "info-section", "What's using your limits"));
    const tabs = el("div", "effort-row usage-tabs");
    const body = el("div", "usage-period");
    let current: "day" | "week" = keys.includes("day") ? "day" : keys[0];
    const render = () => {
      tabs.replaceChildren(
        ...keys.map((k) => {
          const b = el("button", `effort${k === current ? " active" : ""}`, k === "day" ? "Last 24 hours" : "Last 7 days");
          b.addEventListener("click", () => {
            current = k;
            render();
          });
          return b;
        }),
      );
      body.replaceChildren(periodView(periods[current]!, current));
    };
    render();
    card.append(tabs, body);
  }
  card.append(el("div", "muted info-foot", "From Claude Code's /usage (plan limits from claude.ai, the rest from this computer's transcripts)."));
  return card;
}

function periodView(p: Period, period: "day" | "week"): HTMLElement {
  const box = el("div");
  box.append(el("div", "muted", `${p.requests} requests in ${p.sessions} session${p.sessions === 1 ? "" : "s"}`));
  if (p.behaviors.length) {
    const ul = el("ul", "usage-list");
    for (const b of p.behaviors) ul.append(el("li", undefined, `${b.label}: ${Math.round(b.pct)}%`));
    box.append(ul);
  }
  if (p.recommendations.length) {
    box.append(el("div", "info-section", "Recommendations"));
    const ul = el("ul", "usage-list");
    for (const r of p.recommendations) {
      const li = el("li");
      li.append(el("span", undefined, r.title));
      if (r.detail) li.append(el("span", "muted", ` (${r.detail})`));
      ul.append(li);
    }
    box.append(ul);
    if (p.canFix) {
      const fix = el("button", "send", "Fix");
      fix.title = "Claude Code's suggested changes for this session, sent as your next messages";
      fix.addEventListener("click", () => {
        fix.disabled = true;
        post({ type: "usageFix", period });
      });
      box.append(fix);
    }
  }
  if (p.attribution.length) {
    box.append(el("div", "info-section", "Most used"));
    const ul = el("ul", "usage-list");
    for (const a of p.attribution) ul.append(el("li", undefined, `${a.kind} ${a.name}: ${Math.round(a.pct)}%`));
    box.append(ul);
  }
  return box;
}

export function statusCard(s: StatusReport): HTMLElement {
  const card = el("div", "info-card status-card");
  card.append(el("div", "info-title", "Status"));
  const table = el("div", "status-rows");
  for (const r of s.rows) table.append(el("span", "muted", r.label), el("span", "status-value", r.value));
  card.append(table);
  card.append(el("div", "info-section", "MCP servers"));
  if (!s.mcp.length) card.append(el("div", "muted", "None"));
  for (const m of s.mcp) {
    const row = el("div", "status-mcp");
    row.append(el("span", `status-dot ${m.status === "connected" ? "ok" : m.status === "failed" ? "bad" : ""}`, "●"), el("span", undefined, m.name), el("span", "muted", m.status));
    card.append(row);
  }
  return card;
}

/** The header's "•••" menu. [run] executes a panel command ("/usage", "/status", ...). */
export function moreMenu(run: (command: string) => void): HTMLElement {
  const wrap = el("span", "mode more");
  const button = el("button", "link-button", "•••");
  button.title = "Customize, usage, status, report a problem, sign out";
  const menu = el("div", "mode-menu more-menu");
  menu.hidden = true;
  const items: [string, string][] = [
    ["Customize (commands, skills, style, instructions)", "/customize"],
    ["Usage", "/usage"],
    ["Status", "/status"],
    ["Report a Claude Code problem", "/bug"],
    ["Report a Clawed UI problem", "/bug plugin"],
    ["Sign out", "/logout"],
  ];
  for (const [label, command] of items) {
    const b = el("button", "mode-item", label);
    b.addEventListener("click", (ev) => {
      ev.stopPropagation();
      menu.hidden = true;
      run(command);
    });
    menu.append(b);
  }
  button.addEventListener("click", (ev) => {
    ev.stopPropagation();
    menu.hidden = !menu.hidden;
  });
  document.addEventListener("click", () => (menu.hidden = true));
  wrap.append(button, menu);
  return wrap;
}
