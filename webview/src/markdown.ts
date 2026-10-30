// The only way text from Claude, files or tools becomes HTML in the panel (CLAUDE.md, panel rule 1).
import { Marked, type Tokens } from "marked";
import DOMPurify from "dompurify";
import katex from "katex";

// LaTeX math ($…$, $$…$$, \(…\), \[…\], ```math). KaTeX renders MathML only: Chromium draws it natively, so no KaTeX
// fonts, CSS or inline styles are needed (the CSP allows none). The result is sanitized with the rest of the message.
function tex(source: string, displayMode: boolean): string {
  const html = katex.renderToString(source, { displayMode, output: "mathml", throwOnError: false, trust: false, strict: "ignore" });
  // DOMPurify drops <semantics>/<annotation> but keeps their text, which would show the TeX source next to the formula.
  // Remove the annotation (Copy uses the Markdown source anyway) and unwrap <semantics>.
  return html.replace(/<annotation[\s\S]*?<\/annotation>/g, "").replace(/<\/?semantics>/g, "");
}

type MathToken = { type: string; raw: string; text: string; display: boolean };

const mathBlock = {
  name: "mathBlock",
  level: "block" as const,
  start: (src: string) => src.match(/^(?:\$\$|\\\[|```math)/m)?.index,
  tokenizer(src: string): MathToken | undefined {
    const m = /^\$\$([\s\S]+?)\$\$[ \t]*(?:\n|$)/.exec(src) ?? /^\\\[([\s\S]+?)\\\][ \t]*(?:\n|$)/.exec(src) ?? /^```math[ \t]*\n([\s\S]*?)\n```[ \t]*(?:\n|$)/.exec(src);
    return m ? { type: "mathBlock", raw: m[0], text: m[1].trim(), display: true } : undefined;
  },
  renderer: (t: Tokens.Generic) => `<div class="math">${tex(t.text, true)}</div>`,
};

const mathInline = {
  name: "mathInline",
  level: "inline" as const,
  start: (src: string) => src.match(/\$|\\\(/)?.index,
  tokenizer(src: string): MathToken | undefined {
    // $x$ needs no space inside the dollars and no digit right after, so "$5 and $10" stays text.
    const m = /^\$\$([^$]+?)\$\$/.exec(src);
    if (m) return { type: "mathInline", raw: m[0], text: m[1].trim(), display: true };
    const s = /^\$(?![\s$])((?:\\.|[^$\\\n])+?)(?<!\s)\$(?!\d)/.exec(src) ?? /^\\\(([\s\S]+?)\\\)/.exec(src);
    return s ? { type: "mathInline", raw: s[0], text: s[1], display: false } : undefined;
  },
  renderer: (t: Tokens.Generic) => (t.display ? `<span class="math-inline-display">${tex(t.text, true)}</span>` : tex(t.text, false)),
};

const marked = new Marked({
  gfm: true,
  breaks: true, // a single newline is a line break, as in chat (v0.16.0: "one per line" came out as one paragraph)
  extensions: [mathBlock, mathInline],
  renderer: {
    // Raw HTML inside Markdown is shown as text, never parsed.
    html({ text }: Tokens.HTML | Tokens.Tag): string {
      return escapeHtml(text);
    },
  },
});

export function renderMarkdown(source: string): string {
  const html = marked.parse(source, { async: false }) as string;
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true, mathMl: true },
    FORBID_TAGS: ["style", "form", "input", "button", "textarea", "select", "iframe", "object", "embed"],
    FORBID_ATTR: ["style"],
    ALLOWED_URI_REGEXP: /^(?:https?:|#)/i, // no javascript:, data:, file: links
  });
}

export function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}
