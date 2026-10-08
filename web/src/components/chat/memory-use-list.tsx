"use client";

import { useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import type { MemoryUse } from "@/lib/memory-use-api";

const VIA_LABELS: Record<MemoryUse["via"], string> = {
  ALWAYS: "항상",
  FACTS: "기억한 사실",
  READ: "찾아 읽음",
};

/**
 * 한 답 아래에 그 답을 만든 실행이 본문을 받은 기억을 접어 보인다. 보일 줄이 없으면 아무것도 그리지 않는다.
 *
 * <p>처음에는 접어 둔다. 답마다 목록이 펼쳐져 있으면 대화를 읽기 어렵다.
 * 실렸어도 답에 쓰지 않은 항목이 섞이므로 「쓴」 이 아니라 「참고한」 이라고 부른다.
 * 제목은 사용자나 모델이 쓴 글이라 평문으로만 그린다(ADR-009).
 */
export function MemoryUseList({ uses }: { uses: MemoryUse[] }) {
  const [expanded, setExpanded] = useState(false);
  if (uses.length === 0) return null;
  return (
    <li
      data-testid="memory-uses"
      className="-mt-4 flex flex-col items-start gap-1 pl-10 text-sm text-muted-foreground"
    >
      <Button
        type="button"
        variant="link"
        size="xs"
        data-testid="memory-uses-toggle"
        aria-expanded={expanded}
        onClick={() => setExpanded((value) => !value)}
      >
        참고한 기억 {uses.length}개
      </Button>
      {expanded ? (
        <ul className="flex flex-col gap-1 px-2">
          {uses.map((use) => (
            <li
              key={`${use.via}:${use.memoryId}`}
              data-testid="memory-use"
              className="flex flex-wrap items-baseline gap-x-2"
            >
              <span className="min-w-0 break-words text-foreground">
                {use.title}
              </span>
              <span className="text-xs">{VIA_LABELS[use.via]}</span>
              {use.scope === "GROUP" ? (
                <span className="text-xs">그룹</span>
              ) : null}
            </li>
          ))}
          <li>
            <Link
              href="/memory"
              prefetch={false}
              className="text-xs text-foreground underline underline-offset-2"
            >
              기억 화면에서 고치기
            </Link>
          </li>
        </ul>
      ) : null}
    </li>
  );
}
