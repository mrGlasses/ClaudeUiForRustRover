// JS <-> Kotlin bridge. The closed set of message types lives here and is validated again in Kotlin
// (ChatPanel.kt): the panel can only *ask*, Kotlin decides. See the security rules in CLAUDE.md.

export type ToKotlin =
  | { type: "ready" }
  | { type: "send"; text: string }
  | { type: "interrupt" }
  | { type: "newSession" }
  | { type: "openLink"; url: string };

/** Built by Kotlin (protocol/StreamEvents.kt + ChatPanel.kt): never raw CLI events. */
export type FromKotlin =
  | { type: "hello"; version: string; ideBuild: string }
  | { type: "sessionInfo"; model: string; permissionMode: string }
  | { type: "busy" }
  | { type: "textDelta"; key: string; text: string }
  | { type: "textFinal"; key: string; text: string }
  | { type: "toolUse"; id: string; name: string; summary: string }
  | { type: "toolResult"; toolUseId: string; isError: boolean; preview: string }
  | { type: "notice"; text: string }
  | { type: "turnEnd"; stopReason: string; costUsd?: number; durationMs?: number; isError: boolean }
  | { type: "sessionReset" }
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
