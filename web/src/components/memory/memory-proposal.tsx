"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { decideMemoryProposal } from "@/lib/memory-api";
import { ExpandableRow } from "./expandable-row";
import { MemoryMeta } from "./memory-meta";
import type { Memory } from "./memory-list";

/** 에이전트가 제안해 사람의 검토를 기다리는 기억 한 줄이다. 펼쳐 본문을 보고 받아들이거나 거절한다. */
export function MemoryProposal({
  memory,
  open,
  readAt,
  onToggle,
  onChanged,
}: {
  memory: Memory;
  open: boolean;
  readAt: string;
  onToggle(): void;
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
      response = await decideMemoryProposal(memory.id, action);
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
    <ExpandableRow
      title={memory.title}
      meta={<MemoryMeta memory={memory} readAt={readAt} />}
      open={open}
      onToggle={onToggle}
      tone="review"
    >
      {memory.sensitive ? (
        <p className="text-sm text-muted-foreground">
          민감한 내용이라 여기서는 보이지 않아요.
        </p>
      ) : (
        <p className="whitespace-pre-wrap break-words text-sm">
          {memory.content}
        </p>
      )}
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
    </ExpandableRow>
  );
}
