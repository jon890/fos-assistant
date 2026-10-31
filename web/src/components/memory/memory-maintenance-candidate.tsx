"use client";

import Link from "next/link";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  MAINTENANCE_ACTION_LABELS,
  reviewMaintenanceCandidate,
  type MaintenanceCandidate,
  type MaintenanceReview,
} from "@/lib/memory-maintenance-api";

/**
 * 정비 후보 한 줄이다. 「맞음」 과 「아님」 은 확인 결과만 남기고 기억은 바꾸지 않는다.
 *
 * <p>제목과 근거는 평문으로만 그린다(ADR-009).
 */
export function MemoryMaintenanceCandidate({
  candidate,
  onReviewed,
}: {
  candidate: MaintenanceCandidate;
  /** 확인이 끝났거나 이미 없는 후보다. 목록에서 이 줄을 뺀다. */
  onReviewed(id: number): void;
}) {
  /** 보내는 중인 확인이다. 누른 단추에만 회전 표시를 두고 다른 단추는 잠그기만 한다. */
  const [pending, setPending] = useState<MaintenanceReview | null>(null);
  const [error, setError] = useState<string>();

  async function review(value: MaintenanceReview) {
    setPending(value);
    setError(undefined);
    let response: Response;
    try {
      response = await reviewMaintenanceCandidate(candidate.id, value);
    } catch {
      setError("확인 결과를 남기지 못했어요.");
      return;
    } finally {
      setPending(null);
    }
    // 없거나 고칠 수 없는 후보다. 다시 눌러도 같으므로 줄을 뺀다.
    if (response.ok || response.status === 404) {
      onReviewed(candidate.id);
      return;
    }
    setError("확인 결과를 남기지 못했어요.");
  }

  return (
    <li
      data-testid="memory-maintenance-candidate"
      className="rounded-md border border-border bg-muted p-3 text-sm"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="min-w-0 break-words font-semibold">
          {candidate.title}
        </span>
        <Badge variant="outline" data-testid="memory-maintenance-action">
          {MAINTENANCE_ACTION_LABELS[candidate.action] ?? candidate.action}
        </Badge>
      </div>
      <p className="mt-1 break-words text-muted-foreground">
        {candidate.summary}
      </p>
      {error ? (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {error}
        </p>
      ) : null}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Button
          size="sm"
          disabled={pending !== null}
          loading={pending === "CORRECT"}
          loadingText="남기는 중"
          aria-label={`${candidate.title} 정비 후보: 맞음`}
          onClick={() => void review("CORRECT")}
        >
          맞음
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={pending !== null}
          loading={pending === "INCORRECT"}
          loadingText="남기는 중"
          aria-label={`${candidate.title} 정비 후보: 아님`}
          onClick={() => void review("INCORRECT")}
        >
          아님
        </Button>
        <Link
          href={`/memory?memory=${candidate.memoryId}`}
          prefetch={false}
          className="text-xs underline underline-offset-2"
          aria-label={`${candidate.title} 기억 열기`}
        >
          기억 열기
        </Link>
      </div>
    </li>
  );
}
