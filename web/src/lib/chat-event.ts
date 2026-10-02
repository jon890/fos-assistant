/**
 * Control Plane 이 대화 스트림으로 보내는 사건이다.
 *
 * <p>`system` 은 대화에 알림 줄이 저장됐다는 사건이다. 글은 `text` 에, 저장된 메시지 번호는 `messageId` 에 온다.
 *
 * <p>`user` 는 대기 메시지를 합쳐 사용자 메시지로 저장했다는 사건이고 `system` 과 같은 칸을 쓴다. `pending` 은
 * 대기 줄이 바뀌었다는 사건이다. 바뀐 내용은 싣지 않으므로 받은 쪽이 대기 줄을 다시 읽는다.
 *
 * <p>`approval` 은 승인 줄이 생겼다는 사건이다. 요청 번호만 `detail` 에 오므로 받은 쪽이 승인 줄을 다시 읽는다.
 */
export type ChatEvent = {
  type:
    | "started"
    | "delta"
    | "tool"
    | "subagent"
    | "step"
    | "switched"
    | "reset"
    | "done"
    | "stopped"
    | "error"
    | "system"
    | "user"
    | "pending"
    | "approval";
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
