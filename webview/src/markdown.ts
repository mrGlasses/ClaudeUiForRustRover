// The only way text from Claude, files or tools becomes HTML in the panel (CLAUDE.md, panel rule 1).
import { Marked, type Tokens } from "marked";
import DOMPurify from "dompurify";

const marked = new Marked({
  gfm: true,
  breaks: true, // a single newline is a line break, as in chat (v0.16.0: "one per line" came out as one paragraph)
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
    USE_PROFILES: { html: true },
    FORBID_TAGS: ["style", "form", "input", "button", "textarea", "select", "iframe", "object", "embed"],
    FORBID_ATTR: ["style"],
    ALLOWED_URI_REGEXP: /^(?:https?:|#)/i, // no javascript:, data:, file: links
  });
}

export function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}
