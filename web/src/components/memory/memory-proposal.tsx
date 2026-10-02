"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { decideMemoryProposal } from "@/lib/memory-api";
import type { Memory } from "./memory-list";

export function MemoryProposal({
  memory,
  onChanged,
}: {
  memory: Memory;
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
    <article className="rounded-md border border-border bg-muted p-4">
      <h3 className="font-semibold">{memory.title}</h3>
      <p className="mt-2 whitespace-pre-wrap text-sm">{memory.content}</p>
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
    </article>
  );
}
