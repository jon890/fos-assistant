"use client";

import { Fragment, useEffect, useLayoutEffect, useRef, useState } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { MessageBubble, type Turn } from "./message-bubble";
import { ActivityBlock } from "./activity/activity-block";
import type { ActivityState } from "./activity/activity-state";
import { WaitingIndicator } from "./waiting-indicator";

type Props = {
  turns: Turn[];
  loading: boolean;
  sending: boolean;
  activity: ActivityState | null;
  conversationId: number | null;
  /** 흐름으로 도는 turn 의 단계 상태. 흐름이 아니면 null 이다 */
  /** 흐름이 오래 걸린다고 한 번 알렸는지 */
  flowIsSlow: boolean;
  turnError: string | null;
  onOpenSaved(executionId: number): void;
  onOpenLive(): void;
};

export function MessageList({
  turns,
  loading,
  sending,
  activity,
  conversationId,
  flowIsSlow,
  turnError,
  onOpenSaved,
  onOpenLive,
}: Props) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const shouldFollow = useRef(true);
  const [hasNewMessage, setHasNewMessage] = useState(false);
  const streamedAnswer = turns.some(
    (turn) => typeof turn.id === "string" && turn.id.startsWith("assistant-") && turn.content,
  );
  const contentVersion = `${turns.map((turn) => `${turn.id}:${turn.content.length}`).join("|")}:${sending}:${activity?.items.length}:${turnError}`;

  useEffect(() => {
    shouldFollow.current = true;
    setHasNewMessage(false);
  }, [conversationId]);

  useLayoutEffect(() => {
    const element = scrollRef.current;
    if (!element || loading) return;
    if (shouldFollow.current) {
      element.scrollTop = element.scrollHeight;
      setHasNewMessage(false);
    } else {
      setHasNewMessage(true);
    }
  }, [contentVersion, loading]);

  const scrollToBottom = () => {
    const element = scrollRef.current;
    if (!element) return;
    shouldFollow.current = true;
    element.scrollTo({ top: element.scrollHeight, behavior: "smooth" });
    setHasNewMessage(false);
  };

  return (
    <div className="relative min-h-0 flex-1">
      <div
        ref={scrollRef}
        data-testid="message-scroll"
        onScroll={(event) => {
          const element = event.currentTarget;
          shouldFollow.current =
            element.scrollHeight - element.scrollTop - element.clientHeight <= 100;
          if (shouldFollow.current) setHasNewMessage(false);
        }}
        className="h-full overflow-y-auto px-1 py-3"
      >
        <div className="mx-auto w-full max-w-3xl">
          {loading ? (
            <div aria-label="메시지를 읽는 중" className="flex flex-col gap-5">
              <Skeleton className="h-[4.25rem]" />
              <Skeleton className="h-[4.25rem]" />
            </div>
          ) : turns.length === 0 && !sending && !activity ? (
            <p className="py-8 text-center text-sm text-muted">무엇이든 물어보세요.</p>
          ) : (
            <ol className="flex flex-col gap-6">
              {turns.map((turn) => {
                const pendingAssistant =
                  typeof turn.id === "string" && turn.id.startsWith("assistant-");
                return (
                  <Fragment key={turn.id}>
                    {pendingAssistant && activity && activity.items.length > 0 ? (
                      <li className="grid min-w-0 grid-cols-[2rem_minmax(0,1fr)] gap-2">
                        <span aria-hidden="true" />
                        <ActivityBlock mode="live" state={activity} slow={flowIsSlow} onOpenPanel={onOpenLive} />
                      </li>
                    ) : null}
                    <MessageBubble turn={turn} conversationId={conversationId} onOpenSaved={onOpenSaved} />
                  </Fragment>
                );
              })}
              {!streamedAnswer && activity && activity.items.length > 0 ? (
                <li className="grid min-w-0 grid-cols-[2rem_minmax(0,1fr)] gap-2">
                  <span aria-hidden="true" />
                  <ActivityBlock mode="live" state={activity} slow={flowIsSlow} onOpenPanel={onOpenLive} />
                </li>
              ) : null}
              {!streamedAnswer && sending && (!activity || activity.items.length === 0) ? (
                <WaitingIndicator />
              ) : null}
              {turnError ? <li data-testid="turn-error" className="rounded-md bg-surface px-3 py-2 text-sm">{turnError}</li> : null}
            </ol>
          )}
        </div>
      </div>
      {hasNewMessage ? (
        <button
          type="button"
          onClick={scrollToBottom}
          className="absolute bottom-3 left-1/2 -translate-x-1/2 rounded-full border border-border bg-background px-3 py-1.5 text-xs shadow"
        >
          새 메시지
        </button>
      ) : null}
    </div>
  );
}
