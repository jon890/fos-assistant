"use client";

import { useRef, useState } from "react";
import type { ReplyTarget } from "@/lib/reply";

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
