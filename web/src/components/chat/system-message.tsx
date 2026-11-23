"use client";

import { AgentBadge, useAnswerAgent } from "@/components/agent/agent-badge";
import { Button } from "@/components/ui/button";
import { ResultArrivalLines } from "./delegation-lines";
import type { Turn } from "./message-types";
import { ROOM_SUMMARY_TITLE, roomSummaryOf } from "@/lib/room";

/**
 * 대화에 남는 알림 줄이다. 사람이 쓴 말도 에이전트의 답도 아니라 복사, 다시 생성, 판 넘기기를 두지 않는다.
 *
 * <p>위임 결과 알림 줄은 저장된 글 대신 결과마다 맡은 에이전트의 이름표 줄을 그린다. 결과 전달이 끝나지 않은 묶음의 마지막 알림
 * 줄에는 상태와 「결과 다시 전달」 을 둔다. 대화방의 요약 줄은 첫 줄만 보이고 요약은 펼쳐 본다.
 * Hermes 내장 위임 결과 줄(`nativeResult`)은 대화 에이전트의 이름표를 앞에 붙이고 저장된 글을 그대로 쓴다.
 */
export function SystemMessage({
  turn,
  onRetryDelivery,
  deliveryRetrying,
}: {
  turn: Turn;
  onRetryDelivery?(deliveryId: number): void;
  deliveryRetrying: boolean;
}) {
  const agent = useAnswerAgent();
  const summary = roomSummaryOf(turn.content);
  const delivery =
    turn.delivery?.status === "FAILED" || turn.delivery?.status === "STOPPED"
      ? turn.delivery
      : null;
  return (
    <li
      data-testid="system-message"
      className="flex min-w-0 flex-col items-center gap-1.5 px-4"
    >
      {turn.deliveredResults && turn.deliveredResults.length > 0 ? (
        <ResultArrivalLines lines={turn.deliveredResults} />
      ) : turn.nativeResult ? (
        <p
          data-testid="native-result-line"
          data-status={turn.nativeResult.status}
          className="flex min-w-0 max-w-full items-center gap-1.5 text-xs text-muted-foreground"
        >
          <AgentBadge agent={agent} size="sm" />
          <span className="min-w-0 break-words">{turn.content}</span>
        </p>
      ) : summary !== null ? (
        <details
          data-testid="room-summary"
          className="min-w-0 max-w-full text-center text-xs text-muted-foreground"
        >
          <summary className="cursor-pointer">
            {ROOM_SUMMARY_TITLE} · 요약 보기
          </summary>
          <p className="mt-1.5 whitespace-pre-wrap break-words text-left">
            {summary}
          </p>
        </details>
      ) : (
        <p className="min-w-0 max-w-full whitespace-pre-wrap break-words text-center text-xs text-muted-foreground">
          {turn.content}
        </p>
      )}
      {delivery ? (
        <div className="flex flex-wrap items-center justify-center gap-2 text-xs text-muted-foreground">
          <span data-testid="delivery-status">
            {delivery.status === "FAILED"
              ? "결과를 정리하지 못했어요"
              : "결과 정리를 중지했어요"}
          </span>
          {/* 읽기 전용 대화는 다시 전달을 넘기지 않는다. 상태만 보이고 단추를 그리지 않는다. */}
          {onRetryDelivery ? (
            <Button
              type="button"
              variant={delivery.status === "FAILED" ? "outline" : "ghost"}
              size="xs"
              data-testid="delivery-retry"
              disabled={deliveryRetrying}
              onClick={() => onRetryDelivery(delivery.id)}
            >
              결과 다시 전달
            </Button>
          ) : null}
        </div>
      ) : null}
    </li>
  );
}
