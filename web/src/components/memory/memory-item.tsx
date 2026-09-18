"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import type { Memory } from "./memory-list";

export function MemoryItem({ memory, canEdit, onChanged }: { memory: Memory; canEdit: boolean; onChanged(): Promise<void> }) {
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  async function update(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget); setBusy(true); setError(undefined);
    const response = await fetch(`/api/memories/${memory.id}`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ content: form.get("content"), alwaysInject: form.get("alwaysInject") === "on" }) });
    setBusy(false);
    if (!response.ok) { setError("Memory를 고치지 못했습니다."); return; }
    setEditing(false); await onChanged();
  }
  async function remove() { setBusy(true); setError(undefined); const response = await fetch(`/api/memories/${memory.id}`, { method: "DELETE" }); setBusy(false); if (!response.ok) { setError("Memory를 지우지 못했습니다."); return; } await onChanged(); }

  return <article className="rounded-md border border-border p-4">
    <h3 className="font-semibold">{memory.title}</h3>
    {memory.omittedFromContext ? <p className="mt-1 text-xs text-danger" data-testid="memory-omitted">길어서 실리지 않음</p> : null}
    {error ? <p role="alert" className="mt-2 text-sm text-danger">{error}</p> : null}
    {editing && canEdit ? <form onSubmit={(event) => void update(event)} className="mt-3"><textarea name="content" defaultValue={memory.content} required className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm" rows={3} /><label className="mt-2 flex items-center gap-2 text-sm"><input name="alwaysInject" type="checkbox" defaultChecked={memory.alwaysInject} />항상 답에 함께 넣기</label><div className="mt-3 flex gap-2"><Button type="submit" size="sm" disabled={busy}>저장</Button><Button type="button" size="sm" variant="secondary" onClick={() => setEditing(false)}>취소</Button></div></form> : <><p className="mt-2 whitespace-pre-wrap text-sm">{memory.content}</p><p className="mt-2 text-xs text-muted">{memory.alwaysInject ? "항상 답에 함께 넣음" : "필요할 때 제목만 넣음"}</p>{canEdit ? <div className="mt-3 flex gap-2"><Button size="sm" variant="secondary" onClick={() => { setError(undefined); setEditing(true); }}>고치기</Button><Button size="sm" variant="ghost" disabled={busy} onClick={() => void remove()}>지우기</Button></div> : null}</>}
  </article>;
}
