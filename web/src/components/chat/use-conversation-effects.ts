"use client";

import { useEffect, useRef } from "react";
import { escapeOnlyClosedTooltip } from "@/components/ui/tooltip-button";
import { readEventStream } from "@/lib/stream";
import { fetchLiveImages, openConversationEvents } from "@/lib/chat-api";
import type { ChatEvent } from "@/lib/chat-event";
import { receiveDelegation } from "@/lib/delegation";
import { applySteerEvent } from "./steer";
import {
  applyDelegationActivity,
  routeDelegationActivity,
} from "./activity/activity-delegation";
import { applyDelegationName } from "./activity/activity-state";
import { parseLiveImages } from "./artifact/live-images";
import { ConversationSessionProps } from "./conversation-session-types";
import { EVENTS_RECONNECT_MS } from "./conversation-session-helpers";
import type { ConversationSessionState } from "./use-conversation-session-state";
import type { ConversationEvents } from "./use-conversation-events";
import type { ConversationControls } from "./use-conversation-controls";

type Context = Pick<
  ConversationSessionState,
  | "pending"
  | "setApprovalRefresh"
  | "setLiveDelegations"
  | "setLiveImages"
  | "panelTarget"
  | "setPanelTarget"
  | "sending"
  | "currentExecutionId"
  | "stopRequested"
  | "setTurns"
  | "setActivity"
  | "heldDelegationActivity"
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
  setLiveDelegations,
  setLiveImages,
  panelTarget,
  setPanelTarget,
  sending,
  currentExecutionId,
  stopRequested,
  setTurns,
  setActivity,
  heldDelegationActivity,
  conversationId,
  runConversationTask,
  resumeAfterReconnect,
  applyConversationEvent,
  stop,
}: Context) {
  const liveImageRequest = useRef(0);
  /** 맡기기 도구 줄에 이름을 붙인 맡긴 실행이다. 같은 맡김의 상태 줄이 다시 와도 다른 도구 줄에 붙이지 않는다 */
  const namedDelegations = useRef(new Set<number>());

  /**
   * 아직 답에 묶이지 않은 그림 목록을 저장한 순서대로 다시 읽는다. 늦게 온 앞 요청의 응답은 버린다.
   * 읽지 못하면 그대로 두고, 다음 사건이나 끝난 뒤의 이력이 맞춘다.
   */
  async function reloadLiveImages(id: string) {
    const request = ++liveImageRequest.current;
    try {
      const response = await fetchLiveImages(id);
      if (!response.ok) return;
      const paths = parseLiveImages(await response.json());
      if (paths !== null && request === liveImageRequest.current)
        setLiveImages(paths);
    } catch {
      // 위와 같다.
    }
  }

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
          // 연결하기 전과 다시 붙는 동안 저장된 그림은 사건을 못 받았을 수 있어 연결 뒤에 맞춘다.
          void reloadLiveImages(id);
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
            // steer 말풍선도 보류하지 않는다. 이 창이 보낸 turn 이 도는 중에 끼운 글이라 그 답 위에 바로 보여야 한다.
            if (event.type === "steer") {
              setTurns((previous) => applySteerEvent(previous, event));
              return;
            }
            // 만든 그림도 보류하지 않는다. 보낸 turn 이 도는 중에 저장되고, 답이 끝나기 전에 보여야 한다.
            // 사건은 순서가 뒤바뀔 수 있어 저장한 순서의 목록을 다시 읽는다.
            if (event.type === "image") {
              void reloadLiveImages(id);
              return;
            }
            // 승인 줄 사건도 보류하지 않는다. 승인 요청은 보낸 turn 이 도는 중에 생긴다.
            if (event.type === "approval") {
              setApprovalRefresh((count) => count + 1);
              return;
            }
            // 맡긴 실행의 도구 사건은 도는 답의 작업 과정에 바로 넣는다. 도는 답이 없으면 버리고 끝난 뒤에는 트리가 그린다.
            // 앞 답이 맡겨 둔 실행의 사건은 지금 도는 다른 답에 넣지 않는다. 실행 번호가 정해지기 전이면 붙잡아 둔다.
            if (event.type === "delegation_activity") {
              const routed = routeDelegationActivity(
                heldDelegationActivity.current,
                currentExecutionId.current,
                event,
              );
              if (routed)
                setActivity(
                  (previous) =>
                    previous && applyDelegationActivity(previous, routed),
                );
              return;
            }
            // 맡긴 줄도 보류하지 않는다. 위임은 보낸 turn 이 도는 중에 시작하고, 끝난 줄은 이력이 아직 모른다.
            if (event.type === "delegation") {
              const line = event.delegation;
              if (line) {
                setLiveDelegations((previous) =>
                  receiveDelegation(previous, line),
                );
                // 도는 답이 맡긴 첫 줄이면 그 답의 맡기기 도구 줄에 맡은 에이전트 이름을 붙인다.
                if (
                  line.parentExecutionId === currentExecutionId.current &&
                  !namedDelegations.current.has(line.executionId)
                ) {
                  namedDelegations.current.add(line.executionId);
                  setActivity(
                    (previous) =>
                      previous &&
                      applyDelegationName(previous, line.agent?.name),
                  );
                }
              }
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

  // turn 이 시작하거나 끝나면 묶이지 않은 그림을 다시 읽는다. 끝난 답에 묶인 그림은 목록에서 빠진다.
  useEffect(() => {
    if (conversationId !== null) void reloadLiveImages(conversationId);
  }, [conversationId, sending]);

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

  return {};
}
export type ConversationEffects = ReturnType<typeof useConversationEffects>;
