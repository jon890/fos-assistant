"use client";

import Link from "next/link";
import { useState } from "react";
import { Markdown } from "./markdown";
import { formatWhen } from "@/lib/format";

export type Turn = {
  id: number | string;
  role: "USER" | "ASSISTANT";
  content: string;
  senderName: string | null;
  createdAt?: string;
  executionId?: number | null;
  /** 이 답이 여러 실행으로 만들어졌다. 그때만 실행 나무로 가는 길을 보인다 */
  hasChildren?: boolean;
  /** 앞 provider 가 막혀 넘어갔으면 그 답을 만든 provider 와 모델. 아니면 null 이다 */
  switchedTo?: string | null;
};

export function MessageBubble({ turn }: { turn: Turn }) {
  const [detailsVisible, setDetailsVisible] = useState(false);
  const user = turn.role === "USER";
  const sentAt = turn.createdAt ? formatWhen(turn.createdAt) : null;

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
        <div className="leading-7">
          <Markdown>{turn.content}</Markdown>
        </div>
        {turn.hasChildren && turn.executionId ? (
          <Link
            href={`/executions/${turn.executionId}`}
            data-testid="flow-tree-link"
            className="mt-2 inline-block text-xs text-muted underline underline-offset-4"
          >
            이 답이 어떻게 만들어졌는지 보기
          </Link>
        ) : null}
      </div>
    </li>
  );
}
