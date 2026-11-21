"use client";

import { X } from "lucide-react";
import { cn } from "cn";
import type { ReplyImage, ReplyView } from "@/lib/reply";

/** 상자의 작은 그림 주소다. 사진은 첨부 경로, 만든 그림은 결과물 경로로 받는다 */
function imageUrl(conversationId: string, image: ReplyImage): string {
  return image.kind === "ATTACHMENT"
    ? `/api/chat/conversations/${conversationId}/attachments/${image.attachmentId}`
    : `/api/chat/conversations/${conversationId}/files/${(image.path ?? "").split("/").map(encodeURIComponent).join("/")}`;
}

/**
 * 집은 것을 작게 보인다(스펙 chat-reference REQ4, REQ6, REQ13). 글은 앞 두 줄, 그림은 작은 그림이다.
 *
 * <p>입력창 위에서는 `onClear` 로 닫기 단추를 두고, 보낸 메시지 위에서는 `onJump` 로 원래 메시지로 간다.
 */
export function ReplyPreview({
  reply,
  conversationId,
  onClear,
  onJump,
  className,
}: {
  reply: ReplyView;
  conversationId: string | null;
  onClear?(): void;
  onJump?(messageId: number): void;
  className?: string;
}) {
  const body = (
    <>
      <span className="text-xs font-medium text-muted-foreground">
        {reply.role === "ASSISTANT" ? "비서의 답" : "내 메시지"}
      </span>
      {reply.excerpt ? (
        <span className="line-clamp-2 whitespace-pre-wrap break-words text-xs">
          {reply.excerpt}
        </span>
      ) : null}
      {reply.images.length > 0 && conversationId !== null ? (
        <span className="mt-1 flex flex-wrap gap-1">
          {reply.images.map((image) =>
            image.deleted ? (
              <span
                key={`${image.kind}-${image.attachmentId ?? image.path}`}
                data-testid="reply-image-gone"
                className="text-xs text-muted-foreground"
              >
                보관 기간이 지나 볼 수 없어요.
              </span>
            ) : (
              // eslint-disable-next-line @next/next/no-img-element -- 로그인 쿠키로 받는 내부 주소라 next/image 최적화를 거치지 않는다
              <img
                key={`${image.kind}-${image.attachmentId ?? image.path}`}
                data-testid="reply-image"
                src={imageUrl(conversationId, image)}
                alt=""
                className="size-10 rounded border border-border object-cover"
              />
            ),
          )}
        </span>
      ) : null}
    </>
  );
  const frame = cn(
    "flex min-w-0 flex-col items-start gap-0.5 rounded-md border-l-2 border-primary bg-muted/60 px-2 py-1 text-left",
    className,
  );
  if (onJump)
    return (
      <button
        type="button"
        data-testid="reply-box"
        aria-label={`원래 메시지로 가기: ${reply.excerpt || "사진이나 그림"}`}
        onClick={() => onJump(reply.messageId)}
        className={cn(frame, "hover:bg-muted")}
      >
        {body}
      </button>
    );
  return (
    <div data-testid="reply-chip" className={cn(frame, "relative pr-8")}>
      {body}
      {onClear ? (
        <button
          type="button"
          aria-label="답장 취소"
          onClick={onClear}
          className="absolute right-1 top-1 rounded p-1 text-muted-foreground hover:bg-background"
        >
          <X aria-hidden="true" className="size-3.5" />
        </button>
      ) : null}
    </div>
  );
}
