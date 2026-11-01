// JS <-> Kotlin bridge. The closed set of message types lives here and is validated again in Kotlin
// (ChatPanel.kt): the panel can only *ask*, Kotlin decides. See the security rules in CLAUDE.md.

export type ToKotlin =
  | { type: "ready" }
  | { type: "send"; text: string; uuid?: string; includeFile?: boolean; includeSelection?: boolean; images?: { mediaType: string; data: string }[] }
  | { type: "interrupt" }
  | { type: "newSession" }
  | { type: "openLink"; url: string }
  | { type: "cursor"; shape: "pointer" | "default" }
  | { type: "copy"; text: string }
  | { type: "signIn" }
  | {
      type: "permissionAnswer";
      requestId: string;
      decision: "allow" | "deny";
      suggestion?: number;
      destination?: string;
      message?: string;
      answers?: Record<string, string | string[]>;
      response?: string;
    }
  | { type: "setMode"; mode: string }
  | { type: "openDiff"; requestId: string }
  | { type: "openPlan"; requestId: string }
  | { type: "mentionQuery"; query: string; seq: number }
  | { type: "historyList"; query: string; filter?: string }
  | { type: "sessionAction"; action: "rename" | "archive" | "unarchive" | "setGroup"; sessionId?: string; ids?: string[]; value?: string }
  | { type: "groupAction"; action: "create" | "rename" | "delete"; name: string; newName?: string }
  | { type: "resume"; sessionId: string }
  | { type: "export"; mode: "file" | "copy" }
  | { type: "checkpoint"; action: "fork" | "rewind" | "forkRewind"; uuid: string }
  | { type: "taskAction"; action: "stop" | "output" | "background"; id: string }
  | { type: "modelMenu" }
  | { type: "usage" }
  | { type: "usageFix"; period: "day" | "week" }
  | { type: "status" }
  | { type: "signOut" }
  | { type: "reportProblem"; kind: "claude" | "plugin" }
  | { type: "modelSet"; what: "model" | "effort" | "ultracode" | "thinking"; value: string | boolean };

/** Built by Kotlin (protocol/StreamEvents.kt + ChatPanel.kt): never raw CLI events. */
export type FromKotlin =
  | { type: "hello"; version: string; ideBuild: string }
  | { type: "settings"; showTimestamps: boolean; modes: { id: string; label: string }[] }
  | import("./permissions").PermissionCard
  | { type: "permissionResolved"; requestId: string; outcome: string }
  | { type: "permissionMode"; mode: string }
  | { type: "sessionInfo"; model: string; permissionMode: string; slashCommands?: string[] }
  | { type: "busy" }
  | { type: "textDelta"; messageId: string; key: string; text: string }
  | { type: "textFinal"; messageId: string; key: string; text: string }
  | { type: "thinkingStart"; key: string }
  | { type: "thinkingDelta"; key: string; text: string }
  | { type: "blockStop"; key: string }
  | { type: "todos"; items: { text: string; status: "pending" | "in_progress" | "completed" }[] }
  | { type: "taskCreate"; toolUseId: string; subject: string; activeForm?: string }
  | { type: "taskCreated"; toolUseId: string; taskId: string }
  | { type: "taskUpdate"; taskId: string; status?: string; activeForm?: string; subject?: string }
  | { type: "toggleThinking" }
  | { type: "toggleFocus" }
  | { type: "context"; file?: string; showFile?: boolean; startLine?: number; endLine?: number; textShared?: boolean }
  | { type: "insertText"; text: string }
  | { type: "attachFiles"; paths: string[] }
  | { type: "historyItems"; items: import("./history").HistoryItem[]; groups?: string[]; filter?: string }
  | { type: "historyLoaded"; title: string; automatic: boolean; fork?: boolean }
  | { type: "historyUser"; text: string; timestamp?: string; uuid?: string }
  | { type: "historyEnd" }
  | { type: "mentionResults"; seq: number; items: string[] }
  | { type: "signIn" }
  | { type: "toolUse"; id: string; name: string; summary: string; canBackground?: boolean }
  | { type: "toolResult"; toolUseId: string; isError: boolean; preview: string }
  | { type: "notice"; text: string }
  | { type: "turnEnd"; stopReason: string; costUsd?: number; durationMs?: number; isError: boolean; cacheTtlMin?: number }
  | ({ type: "modelState" | "contextUsage" } & Record<string, unknown>)
  | { type: "usageLoading" }
  | ({ type: "usageReport" } & import("./account").UsageReport)
  | ({ type: "statusReport" } & import("./account").StatusReport)
  | { type: "queueMessages"; texts: string[] }
  | { type: "sessionReset" }
  | ({ type: "agentTaskStarted" | "agentTaskProgress" | "agentTaskUpdated" | "agentTaskEnded" | "agentTasksLive" | "agentTasksGone" | "agentActivity" | "agentTaskOutput" } & Record<string, unknown>)
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
