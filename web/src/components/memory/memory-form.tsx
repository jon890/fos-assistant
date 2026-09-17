"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";

const fieldClass = "mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm";

export function MemoryForm({ isAdmin, onCreated }: { isAdmin: boolean; onCreated(): Promise<void> }) {
  const [scope, setScope] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

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
      <label className="text-sm">범위<select name="scope" value={scope} onChange={(event) => setScope(event.target.value)} required className={fieldClass}><option value="" disabled>고르세요</option><option value="USER">나만</option>{isAdmin ? <option value="FAMILY">가족 공용</option> : null}</select></label>
      <label className="text-sm">제목<input name="title" required maxLength={200} className={fieldClass} /></label>
    </div>
    <label className="mt-4 block text-sm">내용<textarea name="content" required className={fieldClass} rows={3} /></label>
    <label className="mt-3 flex items-center gap-2 text-sm"><input type="checkbox" name="alwaysInject" />항상 답에 함께 넣기</label>
    {error ? <p className="mt-3 text-sm">{error}</p> : null}
    <Button type="submit" disabled={!scope || busy} className="mt-4">저장</Button>
  </form>;
}
