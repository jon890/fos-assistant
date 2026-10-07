"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { updateMemory } from "@/lib/memory-api";
import type { Memory } from "./memory-list";

/** 펼친 기억의 본문과 「항상 함께 넣기」 를 고치는 양식이다. 실패하면 닫지 않고 오류를 보인다. */
export function MemoryEditForm({
  memory,
  onCancel,
  onSaved,
}: {
  memory: Memory;
  onCancel(): void;
  onSaved(): Promise<void>;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();

  async function update(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setPending(true);
    setError(undefined);
    let response: Response;
    try {
      response = await updateMemory(memory.id, {
        content: form.get("content"),
        alwaysInject: form.get("alwaysInject") === "on",
      });
    } catch {
      setError("기억을 고치지 못했어요.");
      return;
    } finally {
      setPending(false);
    }
    if (!response.ok) {
      setError("기억을 고치지 못했어요.");
      return;
    }
    await onSaved();
  }

  return (
    <form onSubmit={(event) => void update(event)}>
      {error ? (
        <p role="alert" className="mb-2 text-sm text-destructive">
          {error}
        </p>
      ) : null}
      <Label htmlFor={`memory-content-${memory.id}`} className="mb-1">
        내용
      </Label>
      {/* Textarea 의 기본 field-sizing-content 는 rows 를 무시하므로 고정으로 되돌린다. */}
      <Textarea
        id={`memory-content-${memory.id}`}
        name="content"
        defaultValue={memory.content}
        required
        rows={4}
        className="field-sizing-fixed"
      />
      <Label className="mt-2 font-normal">
        <input
          name="alwaysInject"
          type="checkbox"
          defaultChecked={memory.alwaysInject}
          className="accent-primary"
        />
        답을 만들 때 항상 함께 넣기
      </Label>
      <div className="mt-3 flex gap-2">
        <Button
          type="submit"
          size="sm"
          disabled={pending}
          loading={pending}
          loadingText="저장 중"
        >
          저장
        </Button>
        <Button type="button" size="sm" variant="outline" onClick={onCancel}>
          취소
        </Button>
      </div>
    </form>
  );
}
