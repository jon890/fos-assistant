"use client";

import { useRef, useState } from "react";
import { versionOf, type ReplyTarget } from "@/lib/reply";

/**
 * 답장으로 집은 것이다. 이 대화 화면에만 두고 다른 대화로 가거나 새로 고치면 사라진다(스펙 chat-reference DECISION20).
 *
 * <p>입력창이 보내기 함수를 붙잡아 두므로 보낼 때의 집은 것은 `replyRef` 로 읽는다.
 */
export function useReplyTarget() {
  const [replyTo, setReplyState] = useState<ReplyTarget | null>(null);
  const replyRef = useRef<ReplyTarget | null>(null);
  const setReplyTo = (target: ReplyTarget | null) => {
    replyRef.current = target;
    setReplyState(target);
  };
  return { replyTo, replyRef, setReplyTo };
}

/**
 * 원래 메시지로 스크롤하고 잠시 강조한다. 이전 버전이면 먼저 그 버전으로 넘긴다(설계 chat-reference DECISION19).
 *
 * @param onScroll 맨 아래 따라가기를 끄는 등 스크롤하기 직전에 할 일
 */
export function jumpToMessage(
  messageId: number,
  turns: { id: number | string; replacesMessageId?: number | null }[],
  onVersionChange: (slotId: number, index: number) => void,
  content: HTMLElement | null,
  onScroll: () => void,
) {
  const version = versionOf(turns, messageId);
  if (version) onVersionChange(version.slotId, version.index);
  requestAnimationFrame(() => {
    const element = content?.querySelector<HTMLElement>(
      `[data-message-id="${messageId}"]`,
    );
    if (!element) return;
    onScroll();
    element.scrollIntoView({ block: "center" });
    element.dataset.highlight = "true";
    setTimeout(() => delete element.dataset.highlight, 1500);
  });
}
