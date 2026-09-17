"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import type { Memory } from "./memory-list";

export function MemoryProposal({ memory, onChanged }: { memory: Memory; onChanged(): Promise<void> }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  async function decide(action: "accept" | "reject") { setBusy(true); setError(undefined); const response = await fetch(`/api/memories/${memory.id}/${action}`, { method: "POST" }); setBusy(false); if (!response.ok) { setError("제안을 처리하지 못했습니다."); return; } await onChanged(); }
  return <article className="rounded-md border border-border bg-surface p-4"><h3 className="font-semibold">{memory.title}</h3><p className="mt-2 whitespace-pre-wrap text-sm">{memory.content}</p>{error ? <p role="alert" className="mt-2 text-sm text-danger">{error}</p> : null}<div className="mt-3 flex gap-2"><Button size="sm" disabled={busy} onClick={() => void decide("accept")}>받아들이기</Button><Button size="sm" variant="secondary" disabled={busy} onClick={() => void decide("reject")}>물리기</Button></div></article>;
}
