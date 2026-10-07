"use client";

import { describeError } from "@/components/error-message";
import { emptyActivity, failActivity } from "./activity/activity-state";
import type { Turn } from "./message-bubble";
import {
  regenerateLatestAnswer,
  retryDelivery as requestDeliveryRetry,
} from "@/lib/chat-api";
import {
  ErrorPayload,
  ConversationSessionProps,
} from "./conversation-session-types";
import { savedIdsOf, readPayload } from "./conversation-session-helpers";
import type { ConversationSessionState } from "./use-conversation-session-state";
import type { ConversationHistory } from "./use-conversation-history";
import type { ConversationEvents } from "./use-conversation-events";

type Context = Pick<
  ConversationSessionState,
  | "sending"
  | "selectionVersion"
  | "turns"
  | "sentTurnToken"
  | "setSending"
  | "setError"
  | "setTurnError"
  | "setUnknownSkill"
  | "setActivity"
  | "setLiveExpanded"
  | "liveExpandedRef"
  | "setExpandedOnDone"
  | "currentExecutionId"
  | "setExecutionId"
  | "setStopRequested"
  | "setFlowIsSlow"
  | "setTurns"
  | "refresh"
  | "setDeliveryRetrying"
> &
  Pick<ConversationSessionProps, "conversationId"> &
  Pick<
    ConversationHistory,
    | "latestSlots"
    | "refreshMessages"
    | "clearSelectedSlot"
    | "settleInterruptedStream"
  > &
  Pick<ConversationEvents, "consumeTurnStream" | "finishSentTurn">;

export function useConversationRetry({
  sending,
  selectionVersion,
  turns,
  sentTurnToken,
  setSending,
  setError,
  setTurnError,
  setUnknownSkill,
  setActivity,
  setLiveExpanded,
  liveExpandedRef,
  setExpandedOnDone,
  currentExecutionId,
  setExecutionId,
  setStopRequested,
  setFlowIsSlow,
  setTurns,
  refresh,
  setDeliveryRetrying,
  conversationId,
  latestSlots,
  refreshMessages,
  consumeTurnStream,
  clearSelectedSlot,
  settleInterruptedStream,
  finishSentTurn,
}: Context) {
  async function regenerate() {
    if (conversationId === null || sending) return;
    const version = selectionVersion.current;
    const regeneratedSlotId = latestSlots()?.answers.at(-1)?.version.slotId;
    const pendingId = `assistant-regenerate-${Date.now()}`;
    const stream = { started: false, done: false, reportedError: false };
    const savedBefore = savedIdsOf(turns);
    /** 스트림이 끊겨 보는 창으로 넘어갔다. 보기가 입력창을 풀므로 끝낼 때 풀지 않는다. */
    let handedOff = false;
    /** 끊긴 turn 을 판단하며 이력을 이미 다시 읽었다. 실패 처리에서 한 번 더 읽지 않는다. */
    let historyRead = false;
    sentTurnToken.current = pendingId;
    setSending(true);
    setError(null);
    setTurnError(null);
    setUnknownSkill(null);
    setActivity(emptyActivity(Date.now()));
    setLiveExpanded(false);
    liveExpandedRef.current = false;
    setExpandedOnDone(null);
    currentExecutionId.current = null;
    setExecutionId(null);
    setStopRequested(false);
    setFlowIsSlow(false);
    try {
      const response = await regenerateLatestAnswer(conversationId);
      if (!response.ok) {
        const payload = await readPayload<ErrorPayload>(response);
        setError(describeError(payload.code, payload.message));
        if (payload.code === "MESSAGE_NOT_LATEST")
          await refreshMessages(conversationId, version);
        return;
      }
      try {
        await consumeTurnStream(response, version, stream, {
          onDelta: (textDelta) => {
            setTurns((previous) => {
              const current = previous.find((turn) => turn.id === pendingId);
              return current
                ? previous.map((turn) =>
                    turn.id === pendingId
                      ? { ...turn, content: turn.content + textDelta }
                      : turn,
                  )
                : [
                    ...previous,
                    {
                      id: pendingId,
                      role: "ASSISTANT",
                      content: textDelta,
                      senderName: null,
                    },
                  ];
            });
          },
          onReset: () => {
            setTurns((previous) =>
              previous.filter((turn) => turn.id !== pendingId),
            );
          },
          onError: async (event) => {
            setTurns((previous) =>
              previous.filter((turn) => turn.id !== pendingId),
            );
            setTurnError(
              describeError(
                event.code ?? "INTERNAL_ERROR",
                event.message ?? "요청을 처리하지 못했어요.",
              ),
            );
            if (event.code === "MESSAGE_NOT_LATEST")
              await refreshMessages(conversationId, version);
          },
          onDone: async (event) => {
            setTurns((previous) =>
              previous.filter((turn) => turn.id !== pendingId),
            );
            await Promise.all([
              refresh(),
              refreshMessages(event.conversationId!, version),
            ]);
            clearSelectedSlot(regeneratedSlotId);
          },
        });
      } catch (reason) {
        // 끝 사건을 받은 뒤의 실패는 그대로 올린다. 받기 전에 읽기가 깨졌으면 끊긴 것으로 보고 아래에서 묻는다.
        if (stream.done || stream.reportedError) throw reason;
      }
      if (!stream.done && !stream.reportedError) {
        const kind = await settleInterruptedStream(
          version,
          savedBefore,
          pendingId,
          {
            onAnswered: () => clearSelectedSlot(regeneratedSlotId),
            onMissing: async (history) => {
              historyRead = history !== null;
            },
          },
        );
        if (kind === "observing") handedOff = true;
        if (kind !== "missing") return;
        throw new Error(
          describeError("STREAM_INTERRUPTED", "응답 연결이 끊겼어요."),
        );
      }
    } catch (reason) {
      if (selectionVersion.current === version) {
        setTurns((previous) =>
          previous.filter((turn) => turn.id !== pendingId),
        );
        setActivity(
          (previous) => previous && failActivity(previous, Date.now()),
        );
        setTurnError(
          reason instanceof Error
            ? reason.message
            : "답을 다시 만들지 못했어요.",
        );
        if (!historyRead)
          await refreshMessages(conversationId, version).catch(() => {});
      }
    } finally {
      if (selectionVersion.current === version && !handedOff) {
        setSending(false);
        setFlowIsSlow(false);
      }
      void finishSentTurn(pendingId);
    }
  }

  /**
   * 실패하거나 중지한 결과 전달을 저장된 결과만으로 다시 전달하고 그 답을 흘려 그린다. 보낸 turn 과 같은 방식으로
   * 입력창을 막고, 끊긴 스트림과 끝 처리도 다시 생성과 같다.
   */
  async function retryDeliveryTurn(deliveryId: number) {
    if (conversationId === null || sending || sentTurnToken.current !== null)
      return;
    const version = selectionVersion.current;
    const pendingId = `assistant-retry-${Date.now()}`;
    const stream = { started: false, done: false, reportedError: false };
    const savedBefore = savedIdsOf(turns);
    /** 스트림이 끊겨 보는 창으로 넘어갔다. 보기가 입력창을 풀므로 끝낼 때 풀지 않는다. */
    let handedOff = false;
    /** 끊긴 turn 을 판단하며 이력을 이미 다시 읽었다. 실패 처리에서 한 번 더 읽지 않는다. */
    let historyRead = false;
    sentTurnToken.current = pendingId;
    setDeliveryRetrying(true);
    setSending(true);
    setError(null);
    setTurnError(null);
    setUnknownSkill(null);
    setActivity(emptyActivity(Date.now()));
    setLiveExpanded(false);
    liveExpandedRef.current = false;
    setExpandedOnDone(null);
    currentExecutionId.current = null;
    setExecutionId(null);
    setStopRequested(false);
    setFlowIsSlow(false);
    try {
      const response = await requestDeliveryRetry(conversationId, deliveryId);
      if (!response.ok) {
        const payload = await readPayload<ErrorPayload>(response);
        setError(describeError(payload.code, payload.message));
        setActivity(null);
        // 한도와 대화 잠금은 잠시 뒤 다시 누르면 되므로 단추를 그대로 둔다. 나머지는 서버의 상태대로 다시 그린다.
        if (
          payload.code !== "CONVERSATION_BUSY" &&
          payload.code !== "USER_BUSY"
        )
          await refreshMessages(conversationId, version).catch(() => {});
        return;
      }
      try {
        await consumeTurnStream(response, version, stream, {
          onSystem: (event) => {
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
          },
          onDelta: (textDelta) => {
            setTurns((previous) => {
              const current = previous.find((turn) => turn.id === pendingId);
              return current
                ? previous.map((turn) =>
                    turn.id === pendingId
                      ? { ...turn, content: turn.content + textDelta }
                      : turn,
                  )
                : [
                    ...previous,
                    {
                      id: pendingId,
                      role: "ASSISTANT",
                      content: textDelta,
                      senderName: null,
                    },
                  ];
            });
          },
          onReset: () => {
            setTurns((previous) =>
              previous.filter((turn) => turn.id !== pendingId),
            );
          },
          onError: async (event) => {
            setTurns((previous) =>
              previous.filter((turn) => turn.id !== pendingId),
            );
            setTurnError(
              describeError(
                event.code ?? "INTERNAL_ERROR",
                event.message ?? "요청을 처리하지 못했어요.",
              ),
            );
            await refreshMessages(conversationId, version);
          },
          onDone: async (event) => {
            setTurns((previous) =>
              previous.filter((turn) => turn.id !== pendingId),
            );
            await Promise.all([
              refresh(),
              refreshMessages(event.conversationId!, version),
            ]);
          },
        });
      } catch (reason) {
        // 끝 사건을 받은 뒤의 실패는 그대로 올린다. 받기 전에 읽기가 깨졌으면 끊긴 것으로 보고 아래에서 묻는다.
        if (stream.done || stream.reportedError) throw reason;
      }
      if (!stream.done && !stream.reportedError) {
        const kind = await settleInterruptedStream(
          version,
          savedBefore,
          pendingId,
          {
            onMissing: async (history) => {
              historyRead = history !== null;
            },
          },
        );
        if (kind === "observing") handedOff = true;
        if (kind !== "missing") return;
        throw new Error(
          describeError("STREAM_INTERRUPTED", "응답 연결이 끊겼어요."),
        );
      }
    } catch (reason) {
      if (selectionVersion.current === version) {
        setTurns((previous) =>
          previous.filter((turn) => turn.id !== pendingId),
        );
        setActivity(
          (previous) => previous && failActivity(previous, Date.now()),
        );
        setTurnError(
          reason instanceof Error
            ? reason.message
            : "결과를 다시 전달하지 못했어요.",
        );
        if (!historyRead)
          await refreshMessages(conversationId, version).catch(() => {});
      }
    } finally {
      if (selectionVersion.current === version && !handedOff) {
        setSending(false);
        setFlowIsSlow(false);
      }
      setDeliveryRetrying(false);
      void finishSentTurn(pendingId);
    }
  }
  return { regenerate, retryDeliveryTurn };
}
export type ConversationRetry = ReturnType<typeof useConversationRetry>;
