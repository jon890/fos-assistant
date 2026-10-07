"use client";

import { describeError } from "@/components/error-message";
import { emptyActivity } from "./activity/activity-state";
import type { Turn } from "./message-bubble";
import { fetchConversationMessages, fetchRunningTurn } from "@/lib/chat-api";
import { foldVersions } from "@/lib/message-versions";
import {
  RunningTurn,
  InterruptedOutcome,
  ErrorPayload,
  InterruptedHandlers,
  ConversationSessionProps,
} from "./conversation-session-types";
import {
  readPayload,
  answerAfterLastQuestion,
} from "./conversation-session-helpers";
import type { ConversationSessionState } from "./use-conversation-session-state";

type Context = Pick<
  ConversationSessionState,
  | "disposed"
  | "conversationIdRef"
  | "setSending"
  | "setStopRequested"
  | "currentExecutionId"
  | "setExecutionId"
  | "setActivity"
  | "observedTurnFilter"
  | "setObserving"
  | "selectionVersion"
  | "setNotFound"
  | "setTurns"
  | "setFlowIsSlow"
  | "refresh"
  | "setExpandedOnDone"
  | "liveExpandedRef"
  | "setPanelTarget"
  | "turns"
  | "setSelectedVersions"
> &
  Pick<ConversationSessionProps, "onConversationIdChange">;

export function useConversationHistory({
  disposed,
  conversationIdRef,
  setSending,
  setStopRequested,
  currentExecutionId,
  setExecutionId,
  setActivity,
  observedTurnFilter,
  setObserving,
  selectionVersion,
  setNotFound,
  setTurns,
  setFlowIsSlow,
  refresh,
  setExpandedOnDone,
  liveExpandedRef,
  setPanelTarget,
  turns,
  setSelectedVersions,
  onConversationIdChange,
}: Context) {
  function assignConversationId(id: string) {
    if (disposed.current) return;
    conversationIdRef.current = id;
    onConversationIdChange(id);
  }

  /**
   * 도는 turn 을 보기 시작한다. 보낸 창과 같은 상태를 채워 기다리는 표시와 중지가 같이 동작한다.
   *
   * <p>스트림이 끊겨 넘어온 창은 이미 받은 작업 과정을 그대로 두고, 다음 트리 조회가 그것을 덮는다.
   */
  function beginObserving(
    id: string,
    version: number,
    running: RunningTurn,
    sentHere = false,
  ) {
    const startedAt = running.startedAt
      ? Date.parse(running.startedAt)
      : Date.now();
    setSending(true);
    setStopRequested(false);
    currentExecutionId.current = running.executionId;
    setExecutionId(running.executionId);
    setActivity((previous) => ({
      ...emptyActivity(startedAt),
      items: previous?.items ?? [],
    }));
    observedTurnFilter.current = {
      watchedId: running.executionId,
      phase: "waiting",
    };
    setObserving({ conversationId: id, version, sentHere });
  }

  /**
   * 보낸 창의 스트림이 `done`, `stopped`, `error` 없이 끝났을 때 도는 turn 을 묻는다.
   *
   * <p>스트림이 끊겨도 실행은 계속될 수 있다. 실제로 끊긴 뒤 13분을 더 돌아 성공했는데 화면은 그동안 실패로
   * 보였다. 돌고 있으면 보는 창으로 넘어가고, 돌지 않으면 이력을 다시 읽어 답이 저장됐는지 본다.
   *
   * <p>도는 turn 을 이력보다 먼저 묻는다. 대화를 열 때와 같은 까닭이다. 넘어갈 때 받던 답 조각은 치운다.
   * 저장된 답이 아니고, 남으면 기다리는 표시 대신 멈춘 답으로 보인다. 이력을 다시 읽으면 임시 질문도 저장된
   * 질문으로 바뀌고, 보기가 끝나면 이력을 한 번 더 읽어 저장된 것만 남는다.
   *
   * @param savedBefore 보내기 전에 화면에 있던 저장된 메시지의 번호다. 그 밖의 답이 마지막 질문 뒤에 있으면 답이 저장된 것이다.
   * @param streamedId 받던 답 조각의 임시 식별자다.
   */
  async function handOffInterruptedStream(
    version: number,
    savedBefore: ReadonlySet<Turn["id"]>,
    streamedId: string,
  ): Promise<InterruptedOutcome> {
    const id = conversationIdRef.current;
    if (id === null) return { kind: "missing", history: null };
    let running: RunningTurn | null = null;
    try {
      const response = await fetchRunningTurn(id);
      if (response.ok) {
        running = await readPayload<RunningTurn>(response);
      } else if (response.status === 404) {
        const payload = await readPayload<ErrorPayload>(response).catch(
          () => null,
        );
        if (payload?.code === "CONVERSATION_NOT_FOUND") {
          // 대화를 열 때와 보는 중과 같게 대화를 찾을 수 없어요는 화면으로 간다.
          if (selectionVersion.current === version) setNotFound(true);
          return { kind: "gone" };
        }
      }
    } catch {
      // 묻지 못하면 돌지 않는 것으로 보고 이력으로 판단한다.
    }
    if (selectionVersion.current !== version) return { kind: "gone" };
    if (running?.running) {
      setTurns((previous) => previous.filter((turn) => turn.id !== streamedId));
      await refreshMessages(id, version).catch(() => {
        // 이력을 못 읽으면 임시 질문을 둔 채 본다. 보기가 끝날 때 이력을 다시 읽는다.
      });
      if (selectionVersion.current !== version) return { kind: "gone" };
      beginObserving(id, version, running, true);
      return { kind: "observing" };
    }
    let loaded: Turn[];
    try {
      loaded = await refreshMessages(id, version);
    } catch {
      return { kind: "missing", history: null };
    }
    if (selectionVersion.current !== version) return { kind: "gone" };
    const answer = answerAfterLastQuestion(loaded, savedBefore);
    return answer
      ? { kind: "answered", executionId: answer.executionId ?? null }
      : { kind: "missing", history: loaded };
  }

  /**
   * 끊긴 스트림의 결과를 보내기와 다시 생성이 같은 방식으로 마무리한다. 넘어가면 보는 창이 입력창을 풀고, 답이
   * 저장돼 있으면 끝 사건을 받았을 때처럼 정리하며, 답이 없을 때만 호출자에게 넘긴다.
   */
  async function settleInterruptedStream(
    version: number,
    savedBefore: ReadonlySet<Turn["id"]>,
    streamedId: string,
    handlers: InterruptedHandlers,
  ): Promise<InterruptedOutcome["kind"]> {
    const outcome = await handOffInterruptedStream(
      version,
      savedBefore,
      streamedId,
    );
    if (outcome.kind === "answered") {
      setActivity(null);
      setFlowIsSlow(false);
      settleFinishedActivity(outcome.executionId);
      void refresh();
      handlers.onAnswered?.();
    } else if (outcome.kind === "missing") {
      await handlers.onMissing(outcome.history);
    }
    return outcome.kind;
  }

  /**
   * 끝난 turn 의 작업 과정을 저장된 답으로 넘긴다. 펼쳐 둔 상태를 저장된 블록이 이어받고, live 패널은 저장된
   * 트리로 바뀐다. 답의 실행 번호가 없으면 보일 것이 없어 live 패널을 닫는다.
   */
  function settleFinishedActivity(finishedExecutionId: number | null) {
    if (finishedExecutionId !== null) {
      setExpandedOnDone({
        executionId: finishedExecutionId,
        expanded: liveExpandedRef.current,
      });
    }
    // 결과물 패널은 turn 이 끝나도 그대로 둔다.
    setPanelTarget((previous) => {
      if (previous?.kind !== "activity" || previous.target.mode !== "live")
        return previous;
      return finishedExecutionId !== null
        ? {
            kind: "activity",
            target: { mode: "saved", executionId: finishedExecutionId },
          }
        : null;
    });
  }

  /**
   * 보는 상태를 모두 되돌린다. 끝났을 때와 조회가 이어 실패했을 때 쓴다.
   *
   * @param finishedExecutionId 끝난 답의 실행 번호다. 없으면 live 작업 과정 패널을 닫는다.
   */
  function releaseObserving(finishedExecutionId: number | null = null) {
    settleFinishedActivity(finishedExecutionId);
    setSending(false);
    setObserving(null);
    observedTurnFilter.current = null;
    setActivity(null);
    currentExecutionId.current = null;
    setExecutionId(null);
    setStopRequested(false);
    setFlowIsSlow(false);
  }

  async function refreshMessages(id: string, version: number): Promise<Turn[]> {
    const response = await fetchConversationMessages(id);
    if (!response.ok) {
      const payload = await readPayload<ErrorPayload>(response);
      throw new Error(describeError(payload.code, payload.message));
    }
    const loaded = await readPayload<Turn[]>(response);
    if (selectionVersion.current === version) setTurns(loaded);
    return loaded;
  }

  function latestSlots() {
    const saved = turns
      .filter(
        (turn): turn is Turn & { id: number } => typeof turn.id === "number",
      )
      .map((turn) => ({
        ...turn,
        replacesMessageId: turn.replacesMessageId ?? null,
      }));
    return foldVersions(saved, {}).at(-1);
  }

  function clearSelectedSlot(slotId: number | undefined) {
    if (slotId === undefined) return;
    setSelectedVersions((previous) => {
      const next = { ...previous };
      delete next[slotId];
      return next;
    });
  }
  return {
    assignConversationId,
    beginObserving,
    handOffInterruptedStream,
    settleInterruptedStream,
    settleFinishedActivity,
    releaseObserving,
    refreshMessages,
    latestSlots,
    clearSelectedSlot,
  };
}
export type ConversationHistory = ReturnType<typeof useConversationHistory>;
