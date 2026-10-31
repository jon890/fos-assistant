"use client";

import Link from "next/link";
import { useState } from "react";
import {
  reactToMemoryUse,
  type MemoryUse,
  type MemoryUseReaction,
} from "@/lib/memory-use-api";

const LABELS: Record<MemoryUseReaction, string> = {
  IRRELEVANT: "관련 없음",
  WRONG: "틀림",
};

/**
 * 「참고한 기억」 항목 하나의 「관련 없음」, 「틀림」 단추다(ADR-20261015 / memory-maintenance).
 *
 * <p>누르면 판단 피드백 사건 하나만 남고 기억 내용은 바뀌지 않는다. 남긴 뒤에는 고른 단추만 표시로 남기고, 「틀림」 이면
 * 고치는 길인 기억 화면 링크를 함께 보인다. 남긴 표시는 이 화면에만 두고 다시 열면 단추가 다시 보인다.
 */
export function MemoryUseFeedback({
  conversationId,
  use,
}: {
  conversationId: string;
  use: MemoryUse;
}) {
  const [sent, setSent] = useState<MemoryUseReaction | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function react(reaction: MemoryUseReaction) {
    setPending(true);
    setError(null);
    const result = await reactToMemoryUse(conversationId, use, reaction);
    setPending(false);
    if (result.ok) setSent(reaction);
    else setError(result.message);
  }

  if (sent !== null)
    return (
      <span
        data-testid="memory-use-feedback-sent"
        className="flex flex-wrap items-baseline gap-x-2"
      >
        <span>{LABELS[sent]}으로 남겼어요</span>
        {sent === "WRONG" ? (
          <Link
            href="/memory"
            prefetch={false}
            className="underline underline-offset-2"
          >
            기억 화면에서 고치기
          </Link>
        ) : null}
      </span>
    );
  return (
    <span className="flex flex-wrap items-baseline gap-x-2">
      {(Object.keys(LABELS) as MemoryUseReaction[]).map((reaction) => (
        <button
          key={reaction}
          type="button"
          disabled={pending}
          onClick={() => void react(reaction)}
          aria-label={`${use.title} 기억: ${LABELS[reaction]}`}
          className="underline underline-offset-2 hover:text-foreground disabled:opacity-50"
        >
          {LABELS[reaction]}
        </button>
      ))}
      {error !== null ? (
        <span role="alert" className="text-destructive">
          {error}
        </span>
      ) : null}
    </span>
  );
}
