"use client";

import { FileText } from "lucide-react";
import { cn } from "cn";
import {
  assistantAvatar,
  attachmentPlaceholder,
  revealedTime,
} from "./variants";
import { AnswerBody } from "./answer-body";
import { describeError } from "@/components/error-message";
import { formatWhen } from "@/lib/format";
import { artifactNames } from "@/lib/artifact-name";
import { ActivityBlock } from "./activity/activity-block";
import { MessageActions } from "./message-actions";
import type { VersionSlot } from "@/lib/message-versions";
import { VersionSwitcher } from "./version-switcher";
import { parseSkillCommand } from "./skill-command";
import { Button } from "@/components/ui/button";
export type {
  MessageArtifact,
  MessageAttachment,
  MessageDelivery,
  Turn,
} from "./message-types";
import type { MessageArtifact, MessageAttachment, Turn } from "./message-types";
import { SourceReadList } from "./source-read-list";

function AttachmentGallery({
  conversationId,
  attachments,
}: {
  conversationId: string | null;
  attachments: MessageAttachment[];
}) {
  if (attachments.length === 0 || conversationId === null) return null;
  return (
    <div className="mt-2 flex flex-wrap gap-2">
      {attachments.map((attachment) =>
        attachment.visible ? (
          <a
            key={attachment.id}
            href={`/api/chat/conversations/${conversationId}/attachments/${attachment.id}`}
            target="_blank"
            rel="noreferrer"
          >
            <img
              data-testid="message-attachment"
              src={`/api/chat/conversations/${conversationId}/attachments/${attachment.id}`}
              alt={attachment.originalName}
              loading="lazy"
              className="h-24 w-24 rounded-md border border-border object-cover"
            />
          </a>
        ) : (
          <div
            key={attachment.id}
            data-testid="message-attachment-gone"
            className={attachmentPlaceholder({ size: "message" })}
          >
            {describeError("ATTACHMENT_GONE", "사진을 표시할 수 없어요.")}
          </div>
        ),
      )}
    </div>
  );
}

function ArtifactList({
  artifacts,
  onOpen,
}: {
  artifacts: MessageArtifact[];
  onOpen?(path: string, name: string): void;
}) {
  if (artifacts.length === 0) return null;
  const names = artifactNames(artifacts.map((artifact) => artifact.path));
  return (
    <ul className="mt-2 flex flex-col gap-1">
      {artifacts.map((artifact) => {
        const name = names.get(artifact.path) ?? artifact.path;
        const content = (
          <>
            <FileText aria-hidden="true" className="size-4 shrink-0" />
            <span className="min-w-0 truncate">{name}</span>
          </>
        );
        return (
          <li
            key={artifact.path}
            data-testid="message-artifact"
            className="flex min-w-0 items-center gap-2 text-sm"
          >
            {artifact.deleted || !onOpen ? (
              <span
                aria-disabled="true"
                className="flex min-w-0 items-center gap-2 text-muted-foreground opacity-60"
              >
                {content}
              </span>
            ) : (
              <button
                type="button"
                onClick={() => onOpen(artifact.path, name)}
                className="flex min-w-0 items-center gap-2 rounded-sm underline-offset-4 hover:underline"
              >
                {content}
              </button>
            )}
            {artifact.deleted ? (
              <span className="shrink-0 text-xs text-muted-foreground">
                보관 기간이 지나 볼 수 없어요.
              </span>
            ) : null}
          </li>
        );
      })}
    </ul>
  );
}

/** 비서 얼굴과 이름을 먼저 그리고, 그 아래 칸에 기다림 점이나 작업 과정이나 답을 받는다. */
export function AssistantRow({
  header,
  children,
  className,
  ...props
}: React.ComponentProps<"li"> & {
  /** 이름 옆에 두는 것이다. 보낸 시각이 여기 온다 */
  header?: React.ReactNode;
}) {
  return (
    <li
      className={cn(
        "group grid min-w-0 grid-cols-[2rem_minmax(0,1fr)] gap-2",
        className,
      )}
      {...props}
    >
      <span aria-hidden="true" className={assistantAvatar()}>
        비
      </span>
      <div className="min-w-0">
        <div className="mb-1 flex min-h-8 items-center gap-2">
          <span className="text-sm font-medium">비서</span>
          {header}
        </div>
        {children}
      </div>
    </li>
  );
}

export function MessageBubble({
  turn,
  conversationId,
  onOpenSaved,
  initialActivityExpanded,
  latest,
  streaming,
  userVersion,
  answerVersion,
  onVersionChange,
  canRegenerate = false,
  onRegenerate,
  onAnswer,
  nextUserMessage,
  onOpenArtifact,
  skillCommandChip = false,
  liveActivity,
  onRetryDelivery,
  deliveryRetrying = false,
}: {
  turn: Turn;
  conversationId: string | null;
  onOpenSaved(executionId: number): void;
  initialActivityExpanded: boolean;
  latest: boolean;
  streaming: boolean;
  userVersion?: VersionSlot;
  answerVersion?: VersionSlot;
  onVersionChange?(slotId: number, index: number): void;
  canRegenerate?: boolean;
  onRegenerate?(): void;
  /** 이 답 끝의 질문에 답할 수 있을 때만 준다. 마지막 답이고 돌고 있는 turn 이 없을 때다 */
  onAnswer?(text: string): void;
  /** 이 답 바로 다음의 사용자 메시지다. 카드 답을 복원할 때 쓴다 */
  nextUserMessage?: string;
  /** 답 아래 결과물 줄을 누르면 옆 패널에 그 파일을 연다 */
  onOpenArtifact?(messageId: Turn["id"], path: string, name: string): void;
  /** 사용자 메시지가 스킬 커맨드로 시작하면 그 이름을 칩으로 앞에 그린다 */
  skillCommandChip?: boolean;
  /** 답이 흘러나오는 동안의 진행 중 작업 과정이다. 저장된 블록과 같은 자리에 그린다 */
  liveActivity?: React.ReactNode;
  /** 실패하거나 중지한 결과 전달의 「결과 다시 전달」 을 누르면 그 묶음 번호로 부른다 */
  onRetryDelivery?(deliveryId: number): void;
  /** 다시 전달을 보내는 중이면 단추를 막는다 */
  deliveryRetrying?: boolean;
}) {
  const user = turn.role === "USER";
  const sentAt = turn.createdAt ? formatWhen(turn.createdAt) : null;
  const attachments = turn.attachments ?? [];
  // 저장되기 전의 줄은 임시 문자열 id 를 갖는다. 등장 움직임은 그 줄에만 줘서, 대화를 열 때와 답이 저장될 때 다시 움직이지 않게 한다.
  const unsaved = typeof turn.id === "string";

  if (turn.role === "SYSTEM") {
    // 사람이 쓴 말도 비서의 답도 아니다. 복사, 다시 생성, 판 넘기기를 두지 않는다.
    const delivery =
      turn.delivery?.status === "FAILED" || turn.delivery?.status === "STOPPED"
        ? turn.delivery
        : null;
    return (
      <li
        data-testid="system-message"
        className="flex min-w-0 flex-col items-center gap-1.5 px-4"
      >
        <p className="min-w-0 max-w-full whitespace-pre-wrap break-words text-center text-xs text-muted-foreground">
          {turn.content}
        </p>
        {delivery ? (
          <div className="flex flex-wrap items-center justify-center gap-2 text-xs text-muted-foreground">
            <span data-testid="delivery-status">
              {delivery.status === "FAILED"
                ? "결과를 정리하지 못했어요"
                : "결과 정리를 중지했어요"}
            </span>
            <Button
              type="button"
              variant={delivery.status === "FAILED" ? "outline" : "ghost"}
              size="xs"
              data-testid="delivery-retry"
              disabled={deliveryRetrying}
              onClick={() => onRetryDelivery?.(delivery.id)}
            >
              결과 다시 전달
            </Button>
          </div>
        ) : null}
      </li>
    );
  }

  if (user) {
    const command = skillCommandChip ? parseSkillCommand(turn.content) : null;
    return (
      <li
        className="group flex min-w-0 justify-end focus-visible:outline-none"
        tabIndex={0}
      >
        <div
          data-testid="user-message"
          className={cn(
            "relative max-w-[70%] rounded-xl rounded-br-sm bg-primary-soft px-4 py-2.5",
            "group-focus-visible:outline-2 group-focus-visible:outline-ring",
            unsaved && "animate-message-user",
          )}
        >
          <p className="whitespace-pre-wrap break-words text-sm leading-6">
            {command ? (
              <>
                <span
                  data-testid="skill-chip"
                  className="mr-1.5 inline-block rounded-full border border-border bg-background px-2 text-xs font-medium leading-5"
                >
                  /{command.name}
                </span>
                {command.rest}
              </>
            ) : (
              turn.content
            )}
          </p>
          {userVersion && userVersion.count > 1 && onVersionChange ? (
            <div className="mt-2 flex gap-2">
              <VersionSwitcher
                slot={userVersion}
                onChange={(index) => onVersionChange(userVersion.slotId, index)}
              />
            </div>
          ) : null}
          <AttachmentGallery
            conversationId={conversationId}
            attachments={attachments}
          />
          {sentAt ? (
            // 말풍선 바깥 왼쪽에 겹쳐 둔다. 안에 두면 보일 때마다 말풍선이 한 줄 늘어 아래가 밀린다.
            <time
              dateTime={turn.createdAt}
              className={cn(
                revealedTime(),
                "absolute bottom-2 right-full mr-2 whitespace-nowrap",
              )}
            >
              {sentAt}
            </time>
          ) : null}
        </div>
      </li>
    );
  }

  return (
    <AssistantRow
      data-testid="assistant-message"
      className="focus-visible:outline-2 focus-visible:outline-ring"
      tabIndex={0}
      header={
        sentAt ? (
          <time dateTime={turn.createdAt} className={revealedTime()}>
            {sentAt}
          </time>
        ) : null
      }
    >
      {liveActivity ? (
        <div className="mb-2">{liveActivity}</div>
      ) : turn.activity && turn.executionId ? (
        <div className="mb-2">
          <ActivityBlock
            mode="saved"
            summary={turn.activity}
            executionId={turn.executionId}
            cancelled={turn.status === "CANCELLED"}
            initialExpanded={initialActivityExpanded}
            onOpenPanel={() => onOpenSaved(turn.executionId!)}
          />
        </div>
      ) : null}
      {/* 기다림 점이 있던 자리에 답이 들어올 때는 흐려짐으로만 바뀐다. */}
      <div
        data-testid="assistant-body"
        className={cn("leading-7", unsaved && "animate-fade-in")}
      >
        <AnswerBody
          content={turn.content}
          streaming={streaming}
          nextUserMessage={nextUserMessage}
          onAnswer={streaming ? undefined : onAnswer}
        />
      </div>
      {!streaming && !unsaved && turn.sourceReads ? (
        <SourceReadList sourceReads={turn.sourceReads} />
      ) : null}
      {turn.status === "CANCELLED" ? (
        <p
          data-testid="stopped-mark"
          className="mt-2 text-xs text-muted-foreground"
        >
          중지됨
        </p>
      ) : null}
      {!streaming ? (
        <MessageActions
          content={turn.content}
          latest={latest}
          version={answerVersion}
          onVersionChange={(index) =>
            answerVersion && onVersionChange?.(answerVersion.slotId, index)
          }
          canRegenerate={canRegenerate}
          onRegenerate={onRegenerate}
        />
      ) : null}
      <AttachmentGallery
        conversationId={conversationId}
        attachments={attachments}
      />
      <ArtifactList
        artifacts={turn.artifacts ?? []}
        onOpen={
          onOpenArtifact
            ? (path, name) => onOpenArtifact(turn.id, path, name)
            : undefined
        }
      />
    </AssistantRow>
  );
}
