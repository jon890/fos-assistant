"use client";

import { useState } from "react";
import { Markdown } from "./markdown";
import { describeError } from "../error-message";
import { formatWhen } from "@/lib/format";
import type { ActivitySummary } from "@/lib/chat-event";
import { ActivityBlock } from "./activity/activity-block";

/** 대화에 붙은 사진 한 장이다. `ChatDtos.AttachmentView` 를 그대로 받는다 */
export type MessageAttachment = {
  id: number;
  originalName: string;
  byteSize: number;
  visible: boolean;
  expiresAt: string;
};

export type Turn = {
  id: number | string;
  role: "USER" | "ASSISTANT";
  content: string;
  senderName: string | null;
  createdAt?: string;
  executionId?: number | null;
  /** 이 답이 여러 실행으로 만들어졌는지 서버가 알려준다 */
  hasChildren?: boolean;
  /** 앞 provider 가 막혀 넘어갔으면 그 답을 만든 provider 와 모델. 아니면 null 이다 */
  switchedTo?: string | null;
  /** 이 메시지에 붙은 사진들. 지워진 것도 자리를 남기려고 담는다 */
  attachments?: MessageAttachment[];
  activity?: ActivitySummary | null;
};

function AttachmentGallery({
  conversationId,
  attachments,
}: {
  conversationId: number | null;
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
            className="flex h-24 w-24 items-center justify-center rounded-md border border-border bg-surface p-2 text-center text-xs text-muted"
          >
            {describeError("ATTACHMENT_GONE", "사진을 표시할 수 없습니다.")}
          </div>
        ),
      )}
    </div>
  );
}

export function MessageBubble({
  turn,
  conversationId,
  onOpenSaved,
}: {
  turn: Turn;
  conversationId: number | null;
  onOpenSaved(executionId: number): void;
}) {
  const [detailsVisible, setDetailsVisible] = useState(false);
  const user = turn.role === "USER";
  const sentAt = turn.createdAt ? formatWhen(turn.createdAt) : null;
  const attachments = turn.attachments ?? [];

  if (user) {
    return (
      <li
        className="group flex min-w-0 justify-end focus-visible:outline-none"
        tabIndex={0}
        onMouseEnter={() => setDetailsVisible(true)}
        onMouseLeave={() => setDetailsVisible(false)}
        onFocus={() => setDetailsVisible(true)}
        onBlur={() => setDetailsVisible(false)}
      >
        <div
          data-testid="user-message"
          className="max-w-[70%] rounded-3xl bg-brand-soft px-4 py-2.5 group-focus-visible:outline-2 group-focus-visible:outline-brand"
        >
          <p className="whitespace-pre-wrap break-words text-sm leading-6">{turn.content}</p>
          <AttachmentGallery conversationId={conversationId} attachments={attachments} />
          {sentAt && detailsVisible ? (
            <time
              dateTime={turn.createdAt}
              className="mt-1 block text-right text-xs text-muted"
            >
              {sentAt}
            </time>
          ) : null}
        </div>
      </li>
    );
  }

  return (
    <li
      data-testid="assistant-message"
      className="group grid min-w-0 grid-cols-[2rem_minmax(0,1fr)] gap-2 focus-visible:outline-2 focus-visible:outline-brand"
      tabIndex={0}
      onMouseEnter={() => setDetailsVisible(true)}
      onMouseLeave={() => setDetailsVisible(false)}
      onFocus={() => setDetailsVisible(true)}
      onBlur={() => setDetailsVisible(false)}
    >
      <span
        aria-hidden="true"
        className="flex h-8 w-8 items-center justify-center rounded-full bg-brand text-sm font-semibold text-on-brand"
      >
        비
      </span>
      <div className="min-w-0">
        {turn.switchedTo ? (
          <p className="mb-1 text-xs text-muted" data-testid="provider-switched">
            ── 여기부터 {turn.switchedTo} 로 돈다 ──
          </p>
        ) : null}
        <div className="mb-1 flex min-h-8 items-center gap-2">
          <span className="text-sm font-medium">비서</span>
          {sentAt && detailsVisible ? (
            <time
              dateTime={turn.createdAt}
              className="text-xs text-muted"
            >
              {sentAt}
            </time>
          ) : null}
        </div>
        {turn.activity && turn.executionId ? (
          <div className="mb-2"><ActivityBlock mode="saved" summary={turn.activity} executionId={turn.executionId}
            onOpenPanel={() => onOpenSaved(turn.executionId!)} /></div>
        ) : null}
        <div className="leading-7">
          <Markdown>{turn.content}</Markdown>
        </div>
        <AttachmentGallery conversationId={conversationId} attachments={attachments} />
      </div>
    </li>
  );
}
