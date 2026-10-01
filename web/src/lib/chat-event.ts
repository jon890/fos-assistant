/**
 * Control Plane 이 대화 스트림으로 보내는 사건이다.
 *
 * <p>`system` 은 대화에 알림 줄이 저장됐다는 사건이다. 글은 `text` 에, 저장된 메시지 번호는 `messageId` 에 온다.
 */
export type ChatEvent = {
  type:
    | "started" | "delta" | "tool" | "subagent" | "step" | "switched" | "reset" | "done" | "stopped" | "error"
    | "system";
  text?: string | null;
  toolName?: string | null;
  detail?: string | null;
  conversationId?: string | null;
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
