"use client";

import { Fragment, useLayoutEffect, useRef, useState } from "react";
import { cn } from "cn";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { AssistantRow, MessageBubble, type Turn } from "./message-bubble";
import { ActivityBlock } from "./activity/activity-block";
import type { ActivityState } from "./activity/activity-state";
import { WaitingIndicator } from "./waiting-indicator";
import { MemoryCaptureList } from "./memory-capture-list";
import type { MemoryCapture } from "@/lib/memory-capture-api";
import {
  foldVersions,
  isLatestView,
  type VersionSlot,
} from "@/lib/message-versions";

type Props = {
  turns: Turn[];
  loading: boolean;
  sending: boolean;
  activity: ActivityState | null;
  conversationId: string | null;
  /** 흐름이 오래 걸린다고 한 번 알렸는지 */
  flowIsSlow: boolean;
  liveExpanded: boolean;
  onLiveExpandedChange(value: boolean): void;
  expandedOnDone: { executionId: number; expanded: boolean } | null;
  turnError: string | null;
  onOpenSaved(executionId: number): void;
  onOpenLive(): void;
  onRetry?(): void;
  selectedVersions: Record<number, number>;
  onVersionChange(slotId: number, index: number): void;
  onRegenerate(): void;
  /** 마지막 답 끝의 질문에 답한다. 고른 답을 글로 만들어 다음 메시지로 보낸다 */
  onAnswer?(text: string): void;
  /** 답 아래 결과물 줄을 누르면 옆 패널에 그 파일을 연다 */
  onOpenArtifact(messageId: Turn["id"], path: string, name: string): void;
  /** 사용자 메시지 맨 앞의 스킬 커맨드를 칩으로 그린다. 흐름이 붙은 에이전트의 대화는 커맨드를 해석하지 않아 거짓이다 */
  skillCommandChips: boolean;
  /** 실패하거나 중지한 결과 전달을 다시 전달한다 */
  onRetryDelivery?(deliveryId: number): void;
  /** 다시 전달을 보내는 중이다. 알림 줄의 단추를 막는다 */
  deliveryRetrying?: boolean;
  /** 이 대화에서 에이전트가 남긴 기억 기록이다. 각 답 아래에 그 답의 실행 것만 보인다 */
  memoryCaptures?: MemoryCapture[];
  /** 기억 기록을 처리했다. 되돌리거나 거절한 줄의 번호를 주면 그 줄을 먼저 뺀다 */
  onMemoryCapturesChanged?(removedId?: number): void;
};

export function MessageList({
  turns,
  loading,
  sending,
  activity,
  conversationId,
  flowIsSlow,
  liveExpanded,
  onLiveExpandedChange,
  expandedOnDone,
  turnError,
  onOpenSaved,
  onOpenLive,
  onRetry,
  selectedVersions,
  onVersionChange,
  onRegenerate,
  onAnswer,
  onOpenArtifact,
  skillCommandChips,
  onRetryDelivery,
  deliveryRetrying,
  memoryCaptures = [],
  onMemoryCapturesChanged,
}: Props) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const shouldFollow = useRef(true);
  const [hasNewMessage, setHasNewMessage] = useState(false);
  // 대화가 바뀌면 그리는 중에 「새 메시지」 표시를 지운다.
  const [seenConversationId, setSeenConversationId] = useState(conversationId);
  if (seenConversationId !== conversationId) {
    setSeenConversationId(conversationId);
    setHasNewMessage(false);
  }
  const streamedAnswer = turns.some(
    (turn) =>
      typeof turn.id === "string" &&
      turn.id.startsWith("assistant-") &&
      turn.content,
  );
  const persisted = turns
    .filter(
      (turn): turn is Turn & { id: number } => typeof turn.id === "number",
    )
    .map((turn) => ({
      ...turn,
      replacesMessageId: turn.replacesMessageId ?? null,
    }));
  const folded = foldVersions(persisted, selectedVersions);
  const latestView = isLatestView(folded);
  const pendingVisible: {
    turn: Turn;
    userVersion?: VersionSlot;
    answerVersion?: VersionSlot;
    afterSystem?: boolean;
  }[] = turns
    .filter(
      (turn): turn is Turn & { id: string } => typeof turn.id === "string",
    )
    .map((turn) => ({
      turn,
      userVersion: undefined as VersionSlot | undefined,
      answerVersion: undefined as VersionSlot | undefined,
    }));
  const regenerating = pendingVisible.some(({ turn }) =>
    String(turn.id).startsWith("assistant-regenerate-"),
  );
  // `afterSystem` 은 알림 줄이 연 turn 의 답이다. 다시 만들 질문이 없어 다시 생성 단추를 두지 않는다.
  const foldedVisible: {
    turn: Turn;
    userVersion?: VersionSlot;
    answerVersion?: VersionSlot;
    afterSystem?: boolean;
  }[] = folded.flatMap((fold, turnIndex) => {
    return [
      {
        turn: fold.user,
        userVersion: fold.user.role === "SYSTEM" ? undefined : fold.userVersion,
      },
      ...fold.answers.flatMap((answer, answerIndex) =>
        regenerating &&
        turnIndex === folded.length - 1 &&
        answerIndex === fold.answers.length - 1
          ? []
          : [
              {
                turn: answer.message,
                answerVersion: answer.version,
                afterSystem: fold.user.role === "SYSTEM",
              },
            ],
      ),
    ];
  });
  const visible = [...foldedVisible, ...pendingVisible];
  const lastVisible = visible.at(-1)?.turn;
  const hasNoAnswer = lastVisible?.role === "USER" && latestView && !sending;
  const contentVersion = `${turns.map((turn) => `${turn.id}:${turn.content.length}`).join("|")}:${sending}:${activity?.items.length}:${turnError}`;

  const hasLiveActivity = activity !== null && activity.items.length > 0;
  // 답이 아직 없으면 기다림 점이나 진행 중 블록이 비서 줄 하나에 들어온다. 답이 흘러나오면 그 답의 줄이 블록을 받는다.
  const pendingActivity = !streamedAnswer && hasLiveActivity;
  const waiting = !streamedAnswer && sending && !hasLiveActivity;
  const liveActivityBlock = activity ? (
    <ActivityBlock
      mode="live"
      state={activity}
      slow={flowIsSlow}
      expanded={liveExpanded}
      onExpandedChange={onLiveExpandedChange}
      onOpenPanel={onOpenLive}
    />
  ) : null;

  // 아래 스크롤 맞춤보다 먼저 돌아야 바뀐 대화의 첫 그림부터 맨 아래를 따라간다.
  useLayoutEffect(() => {
    shouldFollow.current = true;
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
    element.scrollTo({
      top: element.scrollHeight,
      behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches
        ? "auto"
        : "smooth",
    });
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
            element.scrollHeight - element.scrollTop - element.clientHeight <=
            100;
          if (shouldFollow.current) setHasNewMessage(false);
        }}
        // relative 가 없으면 안쪽의 sr-only(absolute) 기준 상자가 스크롤 상자 바깥이 되어 바깥 main 의 스크롤 높이를 늘린다.
        className="relative h-full overflow-y-auto px-1 py-3"
      >
        <div className="mx-auto w-full max-w-3xl">
          {loading ? (
            <div aria-label="메시지를 읽는 중" className="flex flex-col gap-5">
              <Skeleton className="h-[4.25rem]" />
              <Skeleton className="h-[4.25rem]" />
            </div>
          ) : turns.length === 0 && !sending && !activity ? (
            <p className="py-8 text-center text-sm text-muted-foreground">
              무엇이든 물어보세요.
            </p>
          ) : (
            <ol className="flex flex-col gap-6">
              {visible.map(
                ({ turn, userVersion, answerVersion, afterSystem }, index) => {
                  const pendingAssistant =
                    typeof turn.id === "string" &&
                    turn.id.startsWith("assistant-");
                  const isLast = visible.at(-1)?.turn.id === turn.id;
                  const nextTurn = visible[index + 1]?.turn;
                  return (
                    <Fragment key={turn.id}>
                      <MessageBubble
                        turn={turn}
                        conversationId={conversationId}
                        onOpenSaved={onOpenSaved}
                        initialActivityExpanded={
                          turn.executionId === expandedOnDone?.executionId &&
                          (expandedOnDone?.expanded ?? false)
                        }
                        latest={isLast}
                        streaming={pendingAssistant && sending}
                        userVersion={userVersion}
                        answerVersion={answerVersion}
                        onVersionChange={onVersionChange}
                        canRegenerate={
                          isLast &&
                          turn.role === "ASSISTANT" &&
                          !afterSystem &&
                          latestView &&
                          !sending
                        }
                        onRegenerate={onRegenerate}
                        onAnswer={
                          isLast &&
                          turn.role === "ASSISTANT" &&
                          latestView &&
                          !sending
                            ? onAnswer
                            : undefined
                        }
                        nextUserMessage={
                          turn.role === "ASSISTANT" && nextTurn?.role === "USER"
                            ? nextTurn.content
                            : undefined
                        }
                        onOpenArtifact={onOpenArtifact}
                        skillCommandChip={skillCommandChips}
                        onRetryDelivery={onRetryDelivery}
                        deliveryRetrying={deliveryRetrying}
                        liveActivity={
                          pendingAssistant && hasLiveActivity
                            ? liveActivityBlock
                            : undefined
                        }
                      />
                      {turn.role === "ASSISTANT" &&
                      typeof turn.executionId === "number" &&
                      onMemoryCapturesChanged ? (
                        <MemoryCaptureList
                          captures={memoryCaptures.filter(
                            (capture) =>
                              capture.executionId === turn.executionId,
                          )}
                          onChanged={onMemoryCapturesChanged}
                        />
                      ) : null}
                      {isLast && hasNoAnswer ? (
                        <li
                          data-testid="no-answer"
                          className="-mt-4 flex animate-fade-in items-center justify-end gap-2 text-xs text-muted-foreground"
                        >
                          <span>답을 받지 못했어요</span>
                          {onRetry ? (
                            <Button
                              type="button"
                              variant="link"
                              size="xs"
                              onClick={onRetry}
                            >
                              다시 시도
                            </Button>
                          ) : null}
                        </li>
                      ) : null}
                    </Fragment>
                  );
                },
              )}
              {pendingActivity || waiting ? (
                <AssistantRow
                  data-testid="pending-assistant"
                  className="animate-message-assistant"
                >
                  {pendingActivity ? liveActivityBlock : <WaitingIndicator />}
                </AssistantRow>
              ) : null}
              {turnError ? (
                <li data-testid="turn-error" className="animate-fade-in">
                  <Notice variant="error">
                    {turnError}
                    {onRetry && !hasNoAnswer ? (
                      <Button
                        type="button"
                        variant="link"
                        size="xs"
                        data-testid="turn-error-retry"
                        onClick={onRetry}
                        className="ml-2"
                      >
                        다시 시도
                      </Button>
                    ) : null}
                  </Notice>
                </li>
              ) : null}
            </ol>
          )}
        </div>
      </div>
      {hasNewMessage ? (
        <button
          type="button"
          onClick={scrollToBottom}
          className={cn(
            "absolute bottom-3 left-1/2 -translate-x-1/2",
            "rounded-full border border-border bg-background px-3 py-1.5 text-xs shadow",
          )}
        >
          새 메시지
        </button>
      ) : null}
    </div>
  );
}
