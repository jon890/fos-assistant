"use client";

import { useEffect } from "react";
import { escapeOnlyClosedTooltip } from "@/components/ui/tooltip-button";
import { readEventStream } from "@/lib/stream";
import { openConversationEvents } from "@/lib/chat-api";
import type { ChatEvent } from "@/lib/chat-event";
import { ConversationSessionProps } from "./conversation-session-types";
import {
  EVENTS_RECONNECT_MS,
  SLOW_FLOW_MS,
} from "./conversation-session-helpers";
import type { ConversationSessionState } from "./use-conversation-session-state";
import type { ConversationEvents } from "./use-conversation-events";
import type { ConversationControls } from "./use-conversation-controls";

type Context = Pick<
  ConversationSessionState,
  | "pending"
  | "setApprovalRefresh"
  | "panelTarget"
  | "setPanelTarget"
  | "sending"
  | "currentExecutionId"
  | "stopRequested"
  | "activity"
  | "flowIsSlow"
  | "setFlowIsSlow"
> &
  Pick<ConversationSessionProps, "conversationId"> &
  Pick<
    ConversationEvents,
    "runConversationTask" | "resumeAfterReconnect" | "applyConversationEvent"
  > &
  Pick<ConversationControls, "stop">;

export function useConversationEffects({
  pending,
  setApprovalRefresh,
  panelTarget,
  setPanelTarget,
  sending,
  currentExecutionId,
  stopRequested,
  activity,
  flowIsSlow,
  setFlowIsSlow,
  conversationId,
  runConversationTask,
  resumeAfterReconnect,
  applyConversationEvent,
  stop,
}: Context) {
  /**
   * 대화가 열려 있는 동안 대화 단위 SSE 를 받는다. 위임 결과로 열린 자동 turn 이 여기로 온다.
   *
   * <p>서버가 끊거나 연결이 깨지면 잠시 뒤 다시 연다. 대화를 옮기거나 화면이 사라지면 연결을 끊는다. 4xx 는
   * 다시 열어도 같으므로 다시 열지 않는다. 다시 연 연결에서는 `resumeAfterReconnect` 로 끊긴 사이의 일을 맞춘다.
   */
  useEffect(() => {
    if (conversationId === null) return;
    const id = conversationId;
    const controller = new AbortController();
    let timer: number | undefined;
    let attempts = 0;
    const connect = async () => {
      let reconnect = true;
      const reconnected = attempts > 0;
      attempts += 1;
      try {
        const response = await openConversationEvents(id, controller.signal);
        if (response.ok) {
          // 최초 연결 직전과 다시 붙는 동안 바뀐 대기 줄은 사건을 못 받을 수 있어 연결 뒤에 맞춘다.
          void pending.reload();
          if (reconnected) {
            setApprovalRefresh((count) => count + 1);
            await runConversationTask(() => resumeAfterReconnect(id));
          }
          await readEventStream<ChatEvent>(response, (event) => {
            // 대기 줄 사건은 보류하지 않는다. 이 창이 보낸 turn 이 도는 동안 보류하면 다른 창이 쌓은 대기
            // 메시지가 그 turn 이 끝날 때까지 보이지 않는다. 대기 줄은 turn 의 그림과 겹치지 않는다.
            if (event.type === "pending") {
              void pending.reload();
              return;
            }
            // 승인 줄 사건도 보류하지 않는다. 승인 요청은 보낸 turn 이 도는 중에 생긴다.
            if (event.type === "approval") {
              setApprovalRefresh((count) => count + 1);
              return;
            }
            return runConversationTask(() => applyConversationEvent(id, event));
          });
        } else {
          reconnect = response.status >= 500;
        }
      } catch {
        // 연결이 깨졌다. 끊은 것이 이 화면이 아니면 아래에서 다시 연다.
      }
      if (reconnect && !controller.signal.aborted) {
        timer = window.setTimeout(() => void connect(), EVENTS_RECONNECT_MS);
      }
    };
    void connect();
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [conversationId]);

  useEffect(() => {
    const onEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.isComposing) return;
      // 「중지」 의 풀이만 닫은 Esc 는 막혀 있어도 받는다. 마우스를 올려 둔 채 누른 첫 Esc 가 답을 멈춰야 한다.
      if (event.defaultPrevented && !escapeOnlyClosedTooltip(event)) return;
      if (panelTarget) {
        event.preventDefault();
        setPanelTarget(null);
        return;
      }
      if (sending && currentExecutionId.current !== null && !stopRequested) {
        event.preventDefault();
        void stop();
      }
    };
    window.addEventListener("keydown", onEscape);
    return () => window.removeEventListener("keydown", onEscape);
  }, [panelTarget, sending, stopRequested]);

  /**
   * 흐름이 시작되고 2분이 지나면 한 번 알린다.
   *
   * <p>첫 단계 사건이 올 때 재기 시작한다. 그전에는 이 turn 이 흐름인지 알 수 없다.
   */
  const flowActive =
    activity?.items.some((item) => item.kind === "step") ?? false;

  useEffect(() => {
    if (!flowActive || flowIsSlow) return;
    const timer = setTimeout(() => setFlowIsSlow(true), SLOW_FLOW_MS);
    return () => clearTimeout(timer);
  }, [flowActive, flowIsSlow]);
  return {};
}
export type ConversationEffects = ReturnType<typeof useConversationEffects>;
