// Phase 12a: the bar under the prompt box: model picker (with Effort, Ultracode, extended thinking), context indicator
// (click: /compact) and the prompt-cache clock. The page only asks (modelMenu / modelSet); Kotlin checks every value.
import { post } from "./bridge";

type ModelRow = { value: string; displayName: string; description: string; disabled: boolean; efforts: string[]; resolvedModel?: string };
type Current = { model?: string; effort?: string; ultracode: boolean; ultracodeAvailable: boolean; thinking: boolean };

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

const EFFORT_LABEL: Record<string, string> = { low: "Low", medium: "Medium", high: "High", xhigh: "Extra high", max: "Max" };

export function createModelBar(opts: { compact: () => void; isBusy: () => boolean }) {
  const bar = el("div", "modelbar");
  const wrap = el("span", "model-wrap");
  const button = el("button", "link-button model-button", "Model ▾");
  button.title = "Model, effort and extended thinking (/model)";
  const menu = el("div", "model-menu");
  menu.hidden = true;
  wrap.append(button, menu);
  const context = el("button", "link-button context-meter");
  context.hidden = true;
  const cache = el("span", "cache-clock");
  cache.hidden = true;
  bar.append(wrap, context, cache);
  const extra = el("span", "modelbar-extra");
  bar.append(extra);

  let models: ModelRow[] = [];
  let current: Current | null = null;
  let sessionModel = ""; // from system/init, before the picker was ever opened

  const isCurrent = (m: ModelRow) => !!current?.model && (m.value === current.model || m.resolvedModel === current.model);

  function label(): void {
    const row = models.find(isCurrent);
    const name = row?.displayName ?? current?.model ?? sessionModel;
    const effort = current?.effort ? ` · ${EFFORT_LABEL[current.effort] ?? current.effort}` : "";
    button.textContent = `${name || "Model"}${effort}${current?.ultracode ? " · Ultracode" : ""} ▾`;
  }

  function renderMenu(): void {
    if (!current) {
      menu.replaceChildren(el("div", "muted model-loading", "Loading models…"));
      return;
    }
    const items: HTMLElement[] = [el("div", "model-section", "Model")];
    for (const m of models) {
      const item = el("button", `mode-item model-item${isCurrent(m) ? " active" : ""}`);
      item.disabled = m.disabled;
      item.append(el("span", "model-name", m.displayName), el("span", "model-desc", m.description));
      item.addEventListener("click", (ev) => {
        ev.stopPropagation();
        if (!isCurrent(m)) set("model", m.value);
      });
      items.push(item);
    }
    const efforts = models.find(isCurrent)?.efforts ?? [];
    if (efforts.length) {
      items.push(el("div", "model-section", "Effort"));
      const row = el("div", "effort-row");
      for (const e of efforts) {
        const b = el("button", `effort${current.effort === e ? " active" : ""}`, EFFORT_LABEL[e] ?? e);
        b.addEventListener("click", (ev) => {
          ev.stopPropagation();
          if (current?.effort !== e) set("effort", e);
        });
        row.append(b);
      }
      items.push(row);
    }
    items.push(toggleRow("Extended thinking", current.thinking, (on) => set("thinking", on)));
    if (current.ultracodeAvailable) items.push(toggleRow("Ultracode", current.ultracode, (on) => set("ultracode", on)));
    menu.replaceChildren(...items);
  }

  function toggleRow(text: string, on: boolean, change: (on: boolean) => void): HTMLElement {
    const label = el("label", "model-toggle");
    const box = el("input");
    box.type = "checkbox";
    box.checked = on;
    box.addEventListener("click", (ev) => ev.stopPropagation());
    box.addEventListener("change", () => change(box.checked));
    label.addEventListener("click", (ev) => ev.stopPropagation());
    label.append(box, el("span", undefined, text));
    return label;
  }

  function set(what: string, value: string | boolean): void {
    if (opts.isBusy()) return;
    menu.classList.add("pending");
    post({ type: "modelSet", what, value } as never);
  }

  function open(): void {
    if (!menu.hidden) {
      menu.hidden = true;
      return;
    }
    menu.hidden = false;
    renderMenu();
    post({ type: "modelMenu" });
  }

  button.addEventListener("click", (ev) => {
    ev.stopPropagation();
    open();
  });
  menu.addEventListener("click", (ev) => ev.stopPropagation());
  document.addEventListener("click", () => (menu.hidden = true));

  // ---------- context ----------
  context.addEventListener("click", () => {
    if (!opts.isBusy()) opts.compact();
  });

  // ---------- prompt cache clock ----------
  let ttlMin = 5;
  let deadline = 0;
  let tick: number | undefined;
  function renderCache(): void {
    if (!deadline) return;
    const left = deadline - Date.now();
    cache.hidden = false;
    cache.classList.toggle("expired", left <= 0);
    if (left <= 0) {
      cache.textContent = "Cache expired";
      cache.title = "The prompt cache has expired: the next message re-reads the whole conversation (slower, uses more of your limits).";
      if (tick !== undefined) window.clearInterval(tick);
      tick = undefined;
      return;
    }
    const min = Math.ceil(left / 60_000);
    cache.textContent = left < 60_000 ? "Cache <1 min" : `Cache ${min} min`;
    cache.title = `Claude keeps this conversation in its prompt cache for ${ttlMin} minutes after each reply. Sending before it expires is faster and cheaper.`;
  }

  return {
    node: bar,
    /** A slot at the end of the bar (the usage ring). */
    extra,
    open,
    handle(msg: { type: string } & Record<string, unknown>): boolean {
      switch (msg.type) {
        case "modelState":
          models = (msg.models as ModelRow[]) ?? [];
          current = msg.current as Current;
          menu.classList.remove("pending");
          label();
          if (!menu.hidden) renderMenu();
          return true;
        case "contextUsage": {
          const pct = Number(msg.percentage) || 0;
          context.hidden = false;
          context.textContent = `Context ${Math.round(pct)}%`;
          context.classList.toggle("high", pct >= 80);
          context.title = `${Number(msg.totalTokens).toLocaleString()} of ${Number(msg.maxTokens).toLocaleString()} tokens in the context window. Click to compact the conversation (/compact).`;
          return true;
        }
        default:
          return false;
      }
    },
    /** system/init: the model the session started with. */
    sessionModel(m: string): void {
      sessionModel = m;
      if (current) current.model = m;
      label();
    },
    /** A turn ended: the cache lives for its TTL from now. */
    turnEnded(ttl?: number): void {
      if (ttl) ttlMin = ttl;
      deadline = Date.now() + ttlMin * 60_000;
      renderCache();
      if (tick === undefined) tick = window.setInterval(renderCache, 15_000);
    },
    reset(): void {
      models = [];
      current = null;
      sessionModel = "";
      label();
      menu.hidden = true;
      context.hidden = true;
      cache.hidden = true;
      deadline = 0;
      ttlMin = 5;
      if (tick !== undefined) window.clearInterval(tick);
      tick = undefined;
    },
  };
}
