"use client";

import { X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { visiblePendingItems, type PendingQueue } from "@/lib/pending-messages";

type Props = {
  queue: PendingQueue;
  onCancel(pendingId: number): void;
  /** 멈춘 대기 줄을 보낸다. 읽기 전용 대화는 넘기지 않아 「보내기」 를 그리지 않고 취소만 남긴다 */
  onRelease?(): void;
  /** 취소나 보내기 요청이 도는 중이다. 그동안 단추를 잠가 같은 요청이 두 번 나가지 않게 한다 */
  busy: boolean;
};

/**
 * 입력창 위의 대기 줄이다. 답이 오는 동안 보낸 글을 쌓인 순서대로 보인다.
 *
 * <p>줄마다 취소 단추가 있다. 중지해서 멈춰 둔 대기 줄에는 「보내기」 를 함께 보인다. 쌓인 글이 없으면 아무것도
 * 그리지 않는다. 이미 steer 말풍선으로 보인 글은 멈춘 줄에서만 그린다. 도는 실행에 끼워 넣는 중인 글은
 * 「끼우는 중」 으로 보이고 취소를 막는다(ADR-20261116 / run-steer).
 */
export function PendingQueueView({ queue, onCancel, onRelease, busy }: Props) {
  const items = visiblePendingItems(queue);
  if (items.length === 0) return null;
  return (
    <div
      data-testid="pending-queue"
      className="mx-auto mb-2 w-full max-w-3xl px-1"
    >
      <div className="flex min-h-7 items-center justify-between gap-2">
        <p className="text-xs text-muted-foreground">
          {queue.held
            ? "중지해서 보내지 않았어요."
            : "답이 끝나면 이어서 보내요."}
        </p>
        {queue.held && onRelease ? (
          <Button
            data-testid="pending-release"
            aria-label="대기 메시지 보내기"
            size="sm"
            disabled={busy}
            onClick={onRelease}
          >
            보내기
          </Button>
        ) : null}
      </div>
      <ul className="mt-1 flex flex-col gap-1">
        {items.map((item) => (
          <li
            key={item.id}
            data-testid="pending-item"
            className="flex min-w-0 items-center gap-2 rounded-full bg-muted pl-3 pr-1"
          >
            <span className="min-w-0 flex-1 truncate text-sm">{item.text}</span>
            {item.steering ? (
              <span
                data-testid="pending-steering"
                className="shrink-0 text-xs text-muted-foreground"
              >
                끼우는 중
              </span>
            ) : null}
            <TooltipButton
              label="대기 메시지 취소"
              disabled={busy || item.steering === true}
              onClick={() => onCancel(item.id)}
              className="shrink-0"
            >
              <X aria-hidden="true" />
            </TooltipButton>
          </li>
        ))}
      </ul>
    </div>
  );
}
