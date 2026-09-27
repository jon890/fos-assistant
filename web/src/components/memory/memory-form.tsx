"use client";

import { useId, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";

export function MemoryForm({ isAdmin, onCreated }: { isAdmin: boolean; onCreated(): Promise<void> }) {
  const [scope, setScope] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const id = useId();

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const element = event.currentTarget;
    const form = new FormData(element);
    setBusy(true); setError("");
    try {
      const response = await fetch("/api/memories", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ scope, title: form.get("title"), content: form.get("content"), alwaysInject: form.get("alwaysInject") === "on" }) });
      if (!response.ok) setError((await response.json()).message ?? "Memory를 저장하지 못했습니다.");
      else { element.reset(); setScope(""); await onCreated(); }
    } catch {
      setError("Memory를 저장하지 못했습니다.");
    } finally { setBusy(false); }
  }

  return <form onSubmit={(event) => void submit(event)} className="mb-8 rounded-md border border-border p-4">
    <h2 className="font-semibold">새 Memory</h2>
    <div className="mt-4 grid gap-4 md:grid-cols-2">
      <div className="grid gap-1.5"><Label htmlFor={`${id}-scope`}>범위</Label><NativeSelect id={`${id}-scope`} name="scope" value={scope} onChange={(event) => setScope(event.target.value)} required><option value="" disabled>고르세요</option><option value="USER">나만</option>{isAdmin ? <option value="FAMILY">가족 공용</option> : null}</NativeSelect></div>
      <div className="grid gap-1.5"><Label htmlFor={`${id}-title`}>제목</Label><Input id={`${id}-title`} name="title" required maxLength={200} /></div>
    </div>
    {/* Textarea 의 기본 field-sizing-content 는 rows 를 무시하므로 고정으로 되돌린다. */}
    <div className="mt-4 grid gap-1.5"><Label htmlFor={`${id}-content`}>내용</Label><Textarea id={`${id}-content`} name="content" required rows={3} className="field-sizing-fixed" /></div>
    <Label className="mt-3 font-normal"><input type="checkbox" name="alwaysInject" className="accent-primary" />항상 답에 함께 넣기</Label>
    {error ? <p className="mt-3 text-sm">{error}</p> : null}
    <Button type="submit" disabled={!scope} loading={busy} loadingText="저장 중" className="mt-4">저장</Button>
  </form>;
}
