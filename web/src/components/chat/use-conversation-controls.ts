"use client";

import { describeError } from "@/components/error-message";
import { fetchRunningTurn, stopExecution } from "@/lib/chat-api";
import { START_FAILURES, startProactiveCheck } from "@/lib/proactive-check";
import {
  ErrorPayload,
  RunningTurn,
  ConversationSessionProps,
} from "./conversation-session-types";
import { readPayload } from "./conversation-session-helpers";
import type { ConversationSessionState } from "./use-conversation-session-state";
import type { ConversationHistory } from "./use-conversation-history";

type Context = Pick<
  ConversationSessionState,
  | "conversationIdRef"
  | "sending"
  | "checkStarting"
  | "selectionVersion"
  | "setCheckStarting"
  | "setCheckError"
  | "autoTurn"
  | "observedTurnFilter"
  | "currentExecutionId"
  | "stopRequested"
  | "setStopRequested"
  | "setError"
> &
  Pick<ConversationSessionProps, "currentConversation"> &
  Pick<ConversationHistory, "beginObserving" | "refreshMessages">;

export function useConversationControls({
  conversationIdRef,
  sending,
  checkStarting,
  selectionVersion,
  setCheckStarting,
  setCheckError,
  autoTurn,
  observedTurnFilter,
  currentExecutionId,
  stopRequested,
  setStopRequested,
  setError,
  currentConversation,
  beginObserving,
  refreshMessages,
}: Context) {
  /**
   * 점검 대화에서 살펴보기를 시작하고 도는 turn 을 따라간다.
   *
   * <p>202 가 오면 대화 단위 SSE 가 시작 알림 줄을 먼저 전했을 수 있다. 그 turn 을 이미 받고 있으면 따로 보지
   * 않는다. 아니면 도는 turn 을 물어 보는 중 상태로 넘긴다. 이미 끝났으면 이력을 다시 읽는다.
   */
  async function startCheck() {
    const id = conversationIdRef.current;
    const code = currentConversation?.agentCode;
    if (id === null || !code || sending || checkStarting) return;
    const version = selectionVersion.current;
    setCheckStarting(true);
    setCheckError(null);
    try {
      const response = await startProactiveCheck(code);
      if (!response.ok) {
        const payload = await readPayload<ErrorPayload>(response).catch(
          () => null,
        );
        setCheckError(
          START_FAILURES[payload?.code ?? ""] ??
            describeError(
              payload?.code ?? "INTERNAL_ERROR",
              payload?.message ?? "요청을 처리하지 못했어요.",
            ),
        );
        return;
      }
      const runningResponse = await fetchRunningTurn(id);
      const running = runningResponse.ok
        ? await readPayload<RunningTurn>(runningResponse)
        : null;
      if (selectionVersion.current !== version) return;
      if (autoTurn.current !== null || observedTurnFilter.current !== null)
        return;
      if (running?.running) {
        beginObserving(id, version, running);
      } else {
        await refreshMessages(id, version);
      }
    } catch {
      if (selectionVersion.current === version)
        setCheckError(describeError("HERMES_UNAVAILABLE", "연결할 수 없어요."));
    } finally {
      setCheckStarting(false);
    }
  }

  async function stop() {
    const executionId = currentExecutionId.current;
    if (executionId === null || stopRequested) return;
    setStopRequested(true);
    try {
      const response = await stopExecution(executionId);
      if (response.status === 202) return;
      const payload = await readPayload<ErrorPayload>(response);
      if (payload.code === "EXECUTION_NOT_RUNNING") return;
      setStopRequested(false);
      setError(describeError(payload.code, payload.message));
    } catch {
      setStopRequested(false);
      setError(
        describeError("HERMES_UNAVAILABLE", "중지 요청을 보내지 못했어요."),
      );
    }
  }
  return { startCheck, stop };
}
export type ConversationControls = ReturnType<typeof useConversationControls>;
