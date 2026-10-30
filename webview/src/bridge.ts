// JS <-> Kotlin bridge. The closed set of message types lives here and is validated again in Kotlin
// (ChatPanel.kt): the panel can only *ask*, Kotlin decides. See the security rules in CLAUDE.md.

export type ToKotlin =
  | { type: "ready" }
  | { type: "ping" }
  | { type: "openLink"; url: string };

export type FromKotlin =
  | { type: "hello"; version: string; ideBuild: string }
  | { type: "pong"; at: string }
  | { type: "error"; message: string };

declare global {
  interface Window {
    /** Defined by Kotlin in a nonce'd script before the bundle runs. */
    __claudeUiPost?: (json: string) => void;
    /** Called by Kotlin with one FromKotlin message. */
    __claudeUiReceive?: (msg: FromKotlin) => void;
  }
}

export function post(msg: ToKotlin): void {
  window.__claudeUiPost?.(JSON.stringify(msg));
}

export function onMessage(handler: (msg: FromKotlin) => void): void {
  window.__claudeUiReceive = handler;
}
