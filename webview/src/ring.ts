// The usage ring (your request, 2026-11-01): like the Claude desktop app, a small circle that fills with the plan's
// 5-hour usage; hover or click shows the limit bars and reset times, with a link to the full /usage card.
// Data: get_usage with skip_behaviors (no transcript scan), after each turn (at most once a minute) and when opened.
import { post } from "./bridge";
import { bar, resets, type UsageReport } from "./account";

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

const SVG = "http://www.w3.org/2000/svg";
const R = 6;
const C = 2 * Math.PI * R;

export function createUsageRing(openFull: () => void) {
  const wrap = el("span", "ring-wrap");
  const button = el("button", "link-button ring-button");
  button.setAttribute("aria-label", "Plan usage");
  const svg = document.createElementNS(SVG, "svg");
  svg.setAttribute("width", "16");
  svg.setAttribute("height", "16");
  svg.setAttribute("viewBox", "0 0 16 16");
  const track = document.createElementNS(SVG, "circle");
  const fill = document.createElementNS(SVG, "circle");
  for (const c of [track, fill]) {
    c.setAttribute("cx", "8");
    c.setAttribute("cy", "8");
    c.setAttribute("r", String(R));
    c.setAttribute("fill", "none");
    c.setAttribute("stroke-width", "2.5");
  }
  track.setAttribute("class", "ring-track");
  fill.setAttribute("class", "ring-fill");
  fill.setAttribute("transform", "rotate(-90 8 8)");
  fill.setAttribute("stroke-dasharray", `0 ${C}`);
  svg.append(track, fill);
  const pctText = el("span", "ring-pct", "");
  button.append(svg, pctText);
  const pop = el("div", "ring-pop");
  pop.hidden = true;
  wrap.append(button, pop);
  wrap.hidden = true; // until the CLI reports plan limits

  let data: UsageReport | null = null;

  function renderPop(): void {
    const items: HTMLElement[] = [el("div", "info-title", `Plan usage${data?.plan ? ` · ${data.plan}` : ""}`)];
    if (!data) items.push(el("div", "muted", "Loading…"));
    else if (data.error) items.push(el("div", "agent-error", data.error));
    else if (!data.limits?.length) items.push(el("div", "muted", data.limitsAvailable === false ? "Plan limits don't apply to this sign-in." : "No plan limits reported."));
    else for (const l of data.limits) items.push(bar(l.label, l.utilization, resets(l.resetsAt)));
    const full = el("button", "link-button", "Full usage (/usage)…");
    full.addEventListener("click", (ev) => {
      ev.stopPropagation();
      pop.hidden = true;
      openFull();
    });
    items.push(full);
    pop.replaceChildren(...items);
  }

  let hoverTimer: number | undefined;
  const open = () => {
    pop.hidden = false;
    renderPop();
  };
  button.addEventListener("click", (ev) => {
    ev.stopPropagation();
    if (pop.hidden) {
      open();
      post({ type: "usageRing" } as never); // fresh numbers
    } else pop.hidden = true;
  });
  button.addEventListener("mouseenter", () => (hoverTimer = window.setTimeout(open, 350)));
  wrap.addEventListener("mouseleave", () => {
    window.clearTimeout(hoverTimer);
    pop.hidden = true;
  });
  pop.addEventListener("click", (ev) => ev.stopPropagation());
  document.addEventListener("click", () => (pop.hidden = true));

  return {
    node: wrap,
    update(u: UsageReport): void {
      data = u;
      const five = u.limits?.find((l) => l.label.startsWith("Current session")) ?? u.limits?.[0];
      wrap.hidden = !five && !u.error;
      if (five) {
        const pct = Math.max(0, Math.min(100, five.utilization));
        fill.setAttribute("stroke-dasharray", `${(pct / 100) * C} ${C}`);
        fill.setAttribute("class", `ring-fill${pct >= 90 ? " high" : pct >= 70 ? " mid" : ""}`);
        pctText.textContent = `${Math.round(pct)}%`;
        button.setAttribute("aria-label", `Plan usage: ${Math.round(pct)}% of the 5-hour limit`);
      }
      if (!pop.hidden) renderPop();
    },
  };
}
