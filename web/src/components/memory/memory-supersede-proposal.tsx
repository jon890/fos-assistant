"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  decideMemoryLink,
  type SupersedeProposal,
} from "@/lib/memory-link-api";

/**
 * 새 기억이 옛 기억을 대신한다는 제안 한 줄이다. 받아들이면 옛 기억은 다음 대화부터 쓰이지 않는다.
 *
 * <p>제목은 모델이 쓴 글이라 평문으로만 그린다(ADR-009).
 */
export function MemorySupersedeProposal({
  proposal,
  onChanged,
}: {
  proposal: SupersedeProposal;
  onChanged(): Promise<void>;
}) {
  /** 보내는 중인 결정이다. 누른 단추에만 회전 표시를 두고 다른 단추는 잠그기만 한다. */
  const [pending, setPending] = useState<"accept" | "reject" | null>(null);
  const [error, setError] = useState<string>();
  async function decide(action: "accept" | "reject") {
    setPending(action);
    setError(undefined);
    let response: Response;
    try {
      response = await decideMemoryLink(proposal.id, action);
    } catch {
      setError("제안을 처리하지 못했어요.");
      return;
    } finally {
      setPending(null);
    }
    if (!response.ok) {
      setError("제안을 처리하지 못했어요.");
      return;
    }
    await onChanged();
  }
  return (
    <li
      data-testid="memory-supersede-proposal"
      className="rounded-md border border-border bg-muted p-3 text-sm"
    >
      <p className="text-xs text-muted-foreground">
        새 기억이 옛 기억을 대신해요
      </p>
      <dl className="mt-2 grid gap-1">
        <div className="flex gap-2">
          <dt className="shrink-0 text-muted-foreground">새 기억</dt>
          <dd className="break-words font-semibold">
            {proposal.newMemory.title}
          </dd>
        </div>
        <div className="flex gap-2">
          <dt className="shrink-0 text-muted-foreground">옛 기억</dt>
          <dd className="break-words">{proposal.oldMemory.title}</dd>
        </div>
      </dl>
      {error ? (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {error}
        </p>
      ) : null}
      <div className="mt-3 flex gap-2">
        <Button
          size="sm"
          disabled={pending !== null}
          loading={pending === "accept"}
          loadingText="받아들이는 중"
          onClick={() => void decide("accept")}
        >
          받아들이기
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={pending !== null}
          loading={pending === "reject"}
          loadingText="거절하는 중"
          onClick={() => void decide("reject")}
        >
          거절
        </Button>
      </div>
    </li>
  );
}
