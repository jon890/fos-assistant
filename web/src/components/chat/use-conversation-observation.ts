"use client";

import { useEffect } from "react";
import { describeError } from "@/components/error-message";
import { fromTree } from "./activity/activity-state";
import type { Turn } from "./message-bubble";
import { fetchConversationMessages, fetchRunningTurn } from "@/lib/chat-api";
import { fetchExecutionTree } from "@/lib/usage-api";
import type { ExecutionTreeResponse } from "@/components/execution/execution-tree";
import {
  RunningTurn,
  ErrorPayload,
  ConversationSessionProps,
} from "./conversation-session-types";
import {
  readPayload,
  answerAfterLastQuestion,
  OBSERVE_MAX_FAILURES,
  OBSERVE_INTERVAL_MS,
} from "./conversation-session-helpers";
import type { ConversationSessionState } from "./use-conversation-session-state";
import type { ConversationHistory } from "./use-conversation-history";

type Context = Pick<
  ConversationSessionState,
  | "selectionVersion"
  | "setNotFound"
  | "setTurns"
  | "setError"
  | "setMessagesLoading"
  | "observing"
  | "currentExecutionId"
  | "setActivity"
  | "refresh"
  | "setExecutionId"
> &
  Pick<ConversationSessionProps, "initialConversationId"> &
  Pick<
    ConversationHistory,
    "beginObserving" | "refreshMessages" | "releaseObserving"
  >;

export function useConversationObservation({
  selectionVersion,
  setNotFound,
  setTurns,
  setError,
  setMessagesLoading,
  observing,
  currentExecutionId,
  setActivity,
  refresh,
  setExecutionId,
  initialConversationId,
  beginObserving,
  refreshMessages,
  releaseObserving,
}: Context) {
  useEffect(() => {
    if (initialConversationId === null) return;
    const version = selectionVersion.current;
    void (async () => {
      try {
        // 도는 turn 을 이력보다 먼저 묻는다. 거꾸로 하면 두 호출 사이에 turn 이 끝났을 때 답이 빠진
        // 이력과 `running=false` 를 함께 받아, 새로 고칠 때까지 답이 보이지 않는다.
        let running: RunningTurn | null = null;
        try {
          const runningResponse = await fetchRunningTurn(initialConversationId);
          if (runningResponse.ok) {
            running = await readPayload<RunningTurn>(runningResponse);
          } else if (runningResponse.status === 404) {
            const payload = await readPayload<ErrorPayload>(
              runningResponse,
            ).catch(() => null);
            if (payload?.code === "CONVERSATION_NOT_FOUND") {
              if (selectionVersion.current === version) setNotFound(true);
              return;
            }
          }
        } catch {
          // 묻지 못하면 돌지 않는 것으로 보고 이력을 읽는다. 이력 읽기가 실패하면 그 오류가 뜬다.
        }
        if (selectionVersion.current !== version) return;
        if (running?.running)
          beginObserving(initialConversationId, version, running);
        const response = await fetchConversationMessages(initialConversationId);
        if (!response.ok) {
          const payload = await readPayload<ErrorPayload>(response);
          if (payload.code === "CONVERSATION_NOT_FOUND") {
            if (selectionVersion.current === version) setNotFound(true);
            return;
          }
          throw new Error(describeError(payload.code, payload.message));
        }
        const messages = await readPayload<Turn[]>(response);
        // 이력을 읽는 동안 대화 단위 SSE 로 먼저 받은 줄이 있으면 덮지 않고 이어 둔다.
        if (selectionVersion.current === version) {
          setTurns((previous) => {
            const loadedIds = new Set(messages.map((turn) => turn.id));
            // 서버 번호가 있는 줄만 잇는다. 문자열 번호의 임시 줄은 저장된 줄과 겹쳐 보일 수 있어 버린다.
            return [
              ...messages,
              ...previous.filter(
                (turn) =>
                  typeof turn.id === "number" && !loadedIds.has(turn.id),
              ),
            ];
          });
        }
      } catch (reason) {
        if (selectionVersion.current === version) {
          setError(
            reason instanceof Error
              ? reason.message
              : "대화 이력을 읽지 못했어요.",
          );
        }
      } finally {
        if (selectionVersion.current === version) {
          setMessagesLoading(false);
        }
      }
    })();
  }, [initialConversationId]);

  /**
   * 다른 창에서 도는 turn 을 주기마다 다시 묻고 작업 과정을 다시 그린다.
   *
   * <p>창이 가려진 동안은 쉬고, 보이게 되면 곧바로 한 번 묻는다. 대화를 옮기거나 화면이 사라지면 정리 함수가
   * 타이머를 지우고, 그 뒤에 온 응답은 선택 판으로 버린다.
   */
  useEffect(() => {
    if (observing === null) return;
    const { conversationId: id, version } = observing;
    let active = true;
    let inFlight = false;
    let failures = 0;
    let timer: number | undefined;
    const current = () => active && selectionVersion.current === version;

    const loadTree = async (treeExecutionId: number) => {
      try {
        const response = await fetchExecutionTree(treeExecutionId);
        if (!response.ok) return;
        const tree = await readPayload<ExecutionTreeResponse>(response);
        if (!current() || currentExecutionId.current !== treeExecutionId)
          return;
        const items = fromTree(tree, { running: true });
        setActivity((previous) => previous && { ...previous, items });
      } catch {
        // 작업 과정은 다음 주기에 다시 읽는다. 기다리는 표시는 그대로 둔다.
      }
    };

    const finish = async () => {
      // 이력을 먼저 읽어 끝난 답의 실행 번호로 작업 과정 패널을 저장된 트리로 바꾼다.
      let loaded: Turn[] | null = null;
      try {
        loaded = await refreshMessages(id, version);
      } catch (reason) {
        if (selectionVersion.current === version) {
          setError(
            reason instanceof Error
              ? reason.message
              : "대화 이력을 읽지 못했어요.",
          );
        }
      }
      if (selectionVersion.current !== version) return;
      releaseObserving(
        loaded
          ? (answerAfterLastQuestion(loaded, new Set())?.executionId ?? null)
          : null,
      );
      void refresh();
    };

    /** 한 번 묻고, 다음 주기에도 물을지를 돌려준다. */
    const pollOnce = async (): Promise<boolean> => {
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
            // 보는 동안 대화가 지워졌다. 되풀이해 물어도 돌아오지 않으므로 곧바로 멈추고 첫 조회와 같은 화면으로 간다.
            if (!current()) return false;
            releaseObserving();
            setNotFound(true);
            return false;
          }
        }
      } catch {
        // 아래에서 실패로 센다.
      }
      if (!current()) return false;
      if (running === null) {
        failures += 1;
        if (failures < OBSERVE_MAX_FAILURES) return true;
        await finish();
        return false;
      }
      failures = 0;
      if (!running.running) {
        await finish();
        return false;
      }
      if (running.executionId === null) {
        // 번호가 아직 붙지 않았다. 중지 단추를 잠가 두고 다음 조회에서 번호를 받는다.
        currentExecutionId.current = null;
        setExecutionId(null);
        return true;
      }
      if (running.executionId !== currentExecutionId.current) {
        // 처음 번호를 받았거나 provider 를 넘어가 번호가 바뀌었다. 중지도 새 번호로 보낸다.
        currentExecutionId.current = running.executionId;
        setExecutionId(running.executionId);
        const startedAt = running.startedAt
          ? Date.parse(running.startedAt)
          : Date.now();
        setActivity((previous) => previous && { ...previous, startedAt });
      }
      await loadTree(running.executionId);
      return current();
    };

    const schedule = () => {
      if (!active || document.hidden) return;
      timer = window.setTimeout(() => {
        timer = undefined;
        void run(pollOnce);
      }, OBSERVE_INTERVAL_MS);
    };

    const run = async (step: () => Promise<boolean>) => {
      inFlight = true;
      let keepGoing = false;
      try {
        keepGoing = await step();
      } finally {
        inFlight = false;
      }
      if (keepGoing) schedule();
    };

    const onVisibilityChange = () => {
      window.clearTimeout(timer);
      timer = undefined;
      if (!document.hidden && !inFlight) void run(pollOnce);
    };

    document.addEventListener("visibilitychange", onVisibilityChange);
    void run(async () => {
      // 대화를 열 때 이미 도는 turn 을 물었다. 번호가 있으면 작업 과정만 곧바로 읽는다.
      if (currentExecutionId.current !== null)
        await loadTree(currentExecutionId.current);
      return current();
    });
    return () => {
      active = false;
      window.clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [observing]);
  return {};
}
export type ConversationObservation = ReturnType<
  typeof useConversationObservation
>;
