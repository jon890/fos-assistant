"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { decideMemoryLink } from "@/lib/memory-link-api";
import type { Memory } from "./memory-list";

/**
 * 다른 기억이 대신한 기억의 펼친 줄에 보이는 안내와 되돌리기 단추다. 되돌리면 이 기억이 다음 대화부터 다시 쓰인다.
 *
 * <p>대신한 기억의 제목은 모델이 쓴 글일 수 있어 평문으로만 그린다(ADR-009).
 */
export function MemorySuperseded({
  superseded,
  canEdit,
  onChanged,
}: {
  superseded: NonNullable<Memory["supersededBy"]>;
  canEdit: boolean;
  onChanged(): Promise<void>;
}) {
  const [restoring, setRestoring] = useState(false);
  const [error, setError] = useState<string>();

  async function restore() {
    setRestoring(true);
    setError(undefined);
    let response: Response;
    try {
      response = await decideMemoryLink(superseded.linkId, "revoke");
    } catch {
      setError("대체를 되돌리지 못했어요.");
      return;
    } finally {
      setRestoring(false);
    }
    if (!response.ok) {
      setError("대체를 되돌리지 못했어요.");
      return;
    }
    await onChanged();
  }

  return (
    <div className="mt-3 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-muted-foreground">
          {superseded.title === null
            ? "다른 기억이 대신해 다음 대화부터 쓰이지 않아요"
            : `대신한 기억: ${superseded.title}`}
        </span>
        {canEdit ? (
          <Button
            size="sm"
            variant="outline"
            data-testid="memory-supersede-revoke"
            loading={restoring}
            loadingText="되돌리는 중"
            onClick={() => void restore()}
          >
            되돌리기
          </Button>
        ) : null}
      </div>
      {error ? (
        <p role="alert" className="mt-2 text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}
