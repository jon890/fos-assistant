"use client";

import { describeError } from "@/components/error-message";
import {
  applyChatEvent,
  emptyActivity,
  failActivity,
} from "./activity/activity-state";
import type { Turn } from "./message-bubble";
import { readEventStream } from "@/lib/stream";
import { fetchRunningTurn } from "@/lib/chat-api";
import type { ChatEvent } from "@/lib/chat-event";
import {
  TurnStreamState,
  TurnStreamCallbacks,
  RunningTurn,
  ErrorPayload,
} from "./conversation-session-types";
import {
  readPayload,
  belongsToObservedTurn,
} from "./conversation-session-helpers";
import type { ConversationSessionState } from "./use-conversation-session-state";
import type { ConversationHistory } from "./use-conversation-history";

type Context = Pick<
  ConversationSessionState,
  | "selectionVersion"
  | "currentExecutionId"
  | "setExecutionId"
  | "setActivity"
  | "setFlowIsSlow"
  | "setLiveExpanded"
  | "liveExpandedRef"
  | "sentTurnToken"
  | "drainingConversationTasks"
  | "conversationTasks"
  | "conversationIdRef"
  | "autoTurn"
  | "setNotFound"
  | "refresh"
  | "setSending"
  | "setApprovalRefresh"
  | "setTurns"
  | "observedTurnFilter"
  | "setTurnError"
  | "setExpandedOnDone"
  | "setStopRequested"
  | "hasPendingItems"
  | "setError"
> &
  Pick<
    ConversationHistory,
    "settleFinishedActivity" | "refreshMessages" | "beginObserving"
  >;

export function useConversationEvents({
  selectionVersion,
  currentExecutionId,
  setExecutionId,
  setActivity,
  setFlowIsSlow,
  setLiveExpanded,
  liveExpandedRef,
  sentTurnToken,
  drainingConversationTasks,
  conversationTasks,
  conversationIdRef,
  autoTurn,
  setNotFound,
  refresh,
  setSending,
  setApprovalRefresh,
  setTurns,
  observedTurnFilter,
  setTurnError,
  setExpandedOnDone,
  setStopRequested,
  hasPendingItems,
  setError,
  settleFinishedActivity,
  refreshMessages,
  beginObserving,
}: Context) {
  /** 모든 turn 요청이 같은 사건과 실행 상태를 처리한다. 메시지 저장 방식만 호출자가 정한다. */
  async function consumeTurnStream(
    response: Response,
    version: number,
    state: TurnStreamState,
    callbacks: TurnStreamCallbacks,
  ) {
    await readEventStream<ChatEvent>(response, async (event) => {
      if (selectionVersion.current !== version) return;
      await applyTurnEvent(event, state, callbacks);
    });
  }

  /** turn 사건 하나를 실행 상태와 작업 과정에 반영한다. 보낸 turn 과 대화 단위 SSE 의 자동 turn 이 함께 쓴다. */
  async function applyTurnEvent(
    event: ChatEvent,
    state: TurnStreamState,
    callbacks: TurnStreamCallbacks,
  ) {
    if (event.type === "system") {
      callbacks.onSystem?.(event);
    } else if (event.type === "started") {
      state.started = true;
      currentExecutionId.current = event.executionId ?? null;
      setExecutionId(event.executionId ?? null);
      await callbacks.onStarted?.(event);
    } else if (event.type === "delta" && event.text) {
      callbacks.onDelta?.(event.text);
    } else if (event.type === "reset") {
      callbacks.onReset?.();
      setActivity((previous) => previous && applyChatEvent(previous, event));
    } else if (["tool", "subagent", "step"].includes(event.type)) {
      setActivity((previous) => previous && applyChatEvent(previous, event));
    } else if (
      (event.type === "done" || event.type === "stopped") &&
      event.conversationId
    ) {
      state.done = true;
      setActivity((previous) => previous && applyChatEvent(previous, event));
      const finishedExecutionId =
        event.executionId ?? currentExecutionId.current;
      settleFinishedActivity(finishedExecutionId);
      await callbacks.onDone?.(event);
      setActivity(null);
      setFlowIsSlow(false);
    } else if (event.type === "error") {
      state.reportedError = true;
      setActivity((previous) => previous && failActivity(previous, Date.now()));
      setLiveExpanded(false);
      liveExpandedRef.current = false;
      setFlowIsSlow(false);
      await callbacks.onError?.(event);
    }
  }

  /**
   * 대화 단위 SSE 로 온 일을 처리한다. 보낸 turn 이 돌거나 보류한 일을 처리하는 중이면 순서대로 보류한다.
   */
  async function runConversationTask(task: () => Promise<void>) {
    if (sentTurnToken.current !== null || drainingConversationTasks.current) {
      conversationTasks.current.push(task);
      return;
    }
    await task();
  }

  /**
   * 대화 단위 SSE 를 다시 연 뒤 끊긴 사이의 일을 맞춘다.
   *
   * <p>받던 자동 turn 이 없으면 이력을 한 번 다시 읽어 그사이 열리고 끝난 자동 turn 을 보인다. 받던 자동 turn 이
   * 있으면 그 끝 사건을 놓쳤을 수 있어 도는 turn 을 묻는다. 돌고 있으면 보는 중 상태로 넘겨 폴링이 끝을 알리고,
   * 돌지 않으면 그 turn 을 정리하고 이력을 다시 읽어 입력창을 푼다. 묻지 못하면 돌지 않는 것으로 본다.
   */
  async function resumeAfterReconnect(id: string) {
    if (conversationIdRef.current !== id) return;
    const version = selectionVersion.current;
    const current = autoTurn.current;
    if (current === null) {
      await refreshMessages(id, version).catch(() => {});
      return;
    }
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
          if (selectionVersion.current === version) setNotFound(true);
          return;
        }
      }
    } catch {
      // 아래에서 돌지 않는 것으로 본다.
    }
    if (selectionVersion.current !== version || autoTurn.current !== current)
      return;
    autoTurn.current = null;
    if (running?.running) {
      beginObserving(id, version, running);
      return;
    }
    settleFinishedActivity(currentExecutionId.current);
    setActivity(null);
    setFlowIsSlow(false);
    currentExecutionId.current = null;
    setExecutionId(null);
    await Promise.all([
      refresh(),
      refreshMessages(id, version).catch(() => {}),
    ]);
    if (selectionVersion.current === version) setSending(false);
  }

  /**
   * 보낸 turn 의 끝 처리가 모두 끝났다. 그동안 보류한 대화 단위 SSE 의 일을 받은 순서대로 처리한다.
   *
   * <p>그사이 대화를 옮겼거나 다음 turn 을 보냈으면 식별자가 달라 아무것도 하지 않는다.
   */
  async function finishSentTurn(token: string) {
    if (sentTurnToken.current !== token) return;
    sentTurnToken.current = null;
    if (drainingConversationTasks.current) return;
    drainingConversationTasks.current = true;
    try {
      while (
        sentTurnToken.current === null &&
        conversationTasks.current.length > 0
      ) {
        const task = conversationTasks.current.shift()!;
        try {
          await task();
        } catch {
          // 사건 하나를 그리지 못해도 뒤의 사건은 그린다. 자동 turn 이 끝나면 이력을 다시 읽어 맞춘다.
        }
      }
    } finally {
      drainingConversationTasks.current = false;
    }
  }

  /**
   * 대화 단위 SSE 로 받은 사건을 그린다. 이 스트림에는 요청한 연결 없이 도는 turn 의 사건만 온다.
   *
   * <p>보는 중인 turn 의 사건은 폴링이 그리므로 버린다. `started` 를 받지 못한 채 온 끝 사건은 이력을 다시 읽어
   * 저장된 답을 보인다. 대기 메시지로 연 turn 도 이 길로 온다. `pending` 사건은 여기까지 오지 않고 받는 자리에서
   * 곧바로 대기 줄을 다시 읽는다.
   */
  async function applyConversationEvent(id: string, event: ChatEvent) {
    if (conversationIdRef.current !== id) return;
    if (event.type === "system") {
      // 승인 줄의 결과는 알림 줄로 온다. 끝난 카드를 치우게 승인 줄도 다시 읽는다.
      setApprovalRefresh((count) => count + 1);
      const line: Turn = {
        id: event.messageId ?? `system-${Date.now()}`,
        role: "SYSTEM",
        content: event.text ?? "",
        senderName: null,
      };
      setTurns((previous) =>
        previous.some((turn) => turn.id === line.id)
          ? previous
          : [...previous, line],
      );
      return;
    }
    if (event.type === "user") {
      // 대기 메시지를 합쳐 저장한 사용자 메시지다. 요청한 연결이 없어 이 사건으로만 온다. 이어 오는 `started` 가 그 답을 연다.
      const line: Turn = {
        id: event.messageId ?? `user-${Date.now()}`,
        role: "USER",
        content: event.text ?? "",
        senderName: null,
      };
      setTurns((previous) =>
        previous.some((turn) => turn.id === line.id)
          ? previous
          : [...previous, line],
      );
      return;
    }
    if (belongsToObservedTurn(observedTurnFilter.current, event)) return;

    if (event.type === "started") {
      autoTurn.current = {
        state: { started: false, done: false, reportedError: false },
        pendingId: `assistant-auto-${Date.now()}`,
      };
      setSending(true);
      setTurnError(null);
      setActivity(emptyActivity(Date.now()));
      setLiveExpanded(false);
      liveExpandedRef.current = false;
      setExpandedOnDone(null);
      setStopRequested(false);
      setFlowIsSlow(false);
    }
    const current = autoTurn.current;
    if (current === null) {
      // 이 창이 연결되기 전에 시작한 turn 이다. 끝나면 저장된 답을 읽어 보인다.
      if (event.type === "done" || event.type === "stopped") {
        await refreshMessages(id, selectionVersion.current).catch(() => {});
      } else if (event.type === "error") {
        if (hasPendingItems.current) {
          // 대기 메시지를 보내려다 사용자 메시지를 저장하기 전에 실패했다. turn 이 열리지 않아 답 자리가 없으므로
          // 입력창 위에 까닭을 알린다. 대기 줄은 멈춘 채 남고 이어 오는 `pending` 사건이 그것을 보인다.
          setError(
            describeError(
              event.code ?? "INTERNAL_ERROR",
              event.message ?? "요청을 처리하지 못했어요.",
            ),
          );
        }
        // `started` 전에 실패한 자동 turn 도 알림 줄의 전달 상태가 바뀌었으므로 이력을 다시 읽어 「결과 다시 전달」 을 그린다.
        await refreshMessages(id, selectionVersion.current).catch(() => {});
      }
      return;
    }
    const { pendingId } = current;
    const finish = async () => {
      if (autoTurn.current === current) autoTurn.current = null;
      if (conversationIdRef.current !== id) return;
      // 이력을 다 읽은 뒤에 입력창을 푼다. 먼저 풀면 그사이 보낸 질문의 임시 줄을 다시 읽은 이력이 덮는다.
      await Promise.all([
        refresh(),
        refreshMessages(id, selectionVersion.current).catch(() => {}),
      ]);
      if (conversationIdRef.current === id) setSending(false);
    };
    await applyTurnEvent(event, current.state, {
      onDelta: (textDelta) => {
        setTurns((previous) => {
          const pending = previous.find((turn) => turn.id === pendingId);
          if (!pending)
            return [
              ...previous,
              {
                id: pendingId,
                role: "ASSISTANT",
                content: textDelta,
                senderName: null,
              },
            ];
          return previous.map((turn) =>
            turn.id === pendingId
              ? { ...turn, content: turn.content + textDelta }
              : turn,
          );
        });
      },
      onReset: () => {
        setTurns((previous) =>
          previous.filter((turn) => turn.id !== pendingId),
        );
      },
      onDone: finish,
      onError: async (failed) => {
        setTurns((previous) =>
          previous.filter((turn) => turn.id !== pendingId),
        );
        setTurnError(
          describeError(
            failed.code ?? "INTERNAL_ERROR",
            failed.message ?? "요청을 처리하지 못했어요.",
          ),
        );
        await finish();
      },
    });
  }
  return {
    consumeTurnStream,
    applyTurnEvent,
    runConversationTask,
    resumeAfterReconnect,
    finishSentTurn,
    applyConversationEvent,
  };
}
export type ConversationEvents = ReturnType<typeof useConversationEvents>;
