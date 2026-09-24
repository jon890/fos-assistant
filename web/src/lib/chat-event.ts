/** Control Plane 이 대화 스트림으로 보내는 사건이다. */
export type ChatEvent = {
  type: "started" | "delta" | "tool" | "subagent" | "step" | "switched" | "reset" | "done" | "stopped" | "error";
  text?: string | null;
  toolName?: string | null;
  detail?: string | null;
  conversationId?: number | null;
  messageId?: number | null;
  executionId?: number | null;
  code?: string | null;
  message?: string | null;
  stepName?: string | null;
  stepState?: "started" | "completed" | "failed" | null;
  phase?: "started" | "completed" | null;
  durationMs?: number | null;
  failed?: boolean | null;
  subagentId?: string | null;
  goal?: string | null;
  model?: string | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
};

export type ActivitySummary = {
  toolCount: number;
  subagentCount: number;
  durationMs: number | null;
};
