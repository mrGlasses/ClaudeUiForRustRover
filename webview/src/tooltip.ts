// The embedded browser (JCEF in the IDE) never shows the native `title` tooltip (v0.27.0 test: the context meter's
// tooltip didn't appear), so the page draws its own. Any element with a `title` gets it; the text is set as textContent.

function el(cls: string): HTMLDivElement {
  const d = document.createElement("div");
  d.className = cls;
  return d;
}

export function installTooltips(): void {
  const tip = el("tooltip");
  tip.hidden = true;
  document.body.append(tip);
  let target: Element | null = null;
  let timer: number | undefined;

  const hide = () => {
    if (timer !== undefined) window.clearTimeout(timer);
    timer = undefined;
    target = null;
    tip.hidden = true;
  };

  const show = () => {
    if (!target || !target.isConnected) return hide();
    // Moved to data-tip so the native (invisible) one never competes; code may set `title` again later.
    if (target.hasAttribute("title")) {
      target.setAttribute("data-tip", target.getAttribute("title") ?? "");
      target.removeAttribute("title");
    }
    const text = target.getAttribute("data-tip") ?? "";
    if (!text) return;
    tip.textContent = text;
    tip.hidden = false;
    const r = target.getBoundingClientRect();
    const w = tip.offsetWidth;
    const h = tip.offsetHeight;
    const left = Math.max(4, Math.min(r.left, window.innerWidth - w - 4));
    const below = r.bottom + 6;
    const top = below + h <= window.innerHeight - 4 ? below : Math.max(4, r.top - h - 6);
    tip.style.left = `${left}px`;
    tip.style.top = `${top}px`;
  };

  document.addEventListener("mouseover", (ev) => {
    const t = (ev.target as Element | null)?.closest("[title], [data-tip]") ?? null;
    if (t === target) return;
    hide();
    if (!t) return;
    if (t.hasAttribute("title")) {
      const tip = t.getAttribute("title") ?? "";
      t.setAttribute("data-tip", tip);
      t.removeAttribute("title");
      // Phase 14c: screen readers still get the text (symbol-only buttons like ☆ and ↶ have no other name).
      if (!t.hasAttribute("aria-label") && !(t.textContent ?? "").trim().match(/[A-Za-z]{3}/)) t.setAttribute("aria-label", tip);
    }
    target = t;
    timer = window.setTimeout(show, 450);
  });
  document.addEventListener("mousedown", hide, true);
  document.addEventListener("scroll", hide, true);
  document.addEventListener("keydown", hide, true);
  window.addEventListener("blur", hide);
}
