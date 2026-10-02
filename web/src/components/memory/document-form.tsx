"use client";

import { useId, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import {
  createDocument,
  type MemoryCollectionOption,
} from "@/lib/memory-document";

export function DocumentForm({
  collections,
  onCreated,
}: {
  collections: MemoryCollectionOption[];
  onCreated(): Promise<void>;
}) {
  const [collection, setCollection] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const id = useId();

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const element = event.currentTarget;
    const form = new FormData(element);
    setBusy(true);
    setError("");
    try {
      const result = await createDocument({
        collection,
        documentKey: String(form.get("documentKey")),
        title: String(form.get("title")),
        content: String(form.get("content")),
        sensitive: form.get("sensitive") === "on",
      });
      if (!result.ok) {
        setError(result.message);
        return;
      }
      element.reset();
      setCollection("");
      await onCreated();
    } finally {
      setBusy(false);
    }
  }

  return (
    <form
      onSubmit={(event) => void submit(event)}
      className="mb-4 rounded-md border border-border p-4"
    >
      <h3 className="font-semibold">새 문서</h3>
      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <div className="grid gap-1.5">
          <Label htmlFor={`${id}-collection`}>영역</Label>
          <NativeSelect
            id={`${id}-collection`}
            value={collection}
            onChange={(event) => setCollection(event.target.value)}
            required
          >
            <option value="" disabled>
              고르세요
            </option>
            {collections.map((option) => (
              <option key={option.key} value={option.key}>
                {option.displayName}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor={`${id}-key`}>문서 이름</Label>
          <Input
            id={`${id}-key`}
            name="documentKey"
            required
            maxLength={128}
            pattern="[a-z0-9][a-z0-9\-]*"
            aria-describedby={`${id}-key-hint`}
          />
          <p id={`${id}-key-hint`} className="text-xs text-muted-foreground">
            영문 소문자, 숫자, 하이픈으로 적어 주세요.
          </p>
        </div>
      </div>
      <div className="mt-4 grid gap-1.5">
        <Label htmlFor={`${id}-title`}>제목</Label>
        <Input id={`${id}-title`} name="title" required maxLength={200} />
      </div>
      {/* Textarea 의 기본 field-sizing-content 는 rows 를 무시하므로 고정으로 되돌린다. */}
      <div className="mt-4 grid gap-1.5">
        <Label htmlFor={`${id}-content`}>내용</Label>
        <Textarea
          id={`${id}-content`}
          name="content"
          required
          rows={8}
          maxLength={12000}
          className="field-sizing-fixed"
        />
      </div>
      <Label className="mt-3 font-normal">
        <input type="checkbox" name="sensitive" className="accent-primary" />
        민감한 내용이에요. 암호화해서 저장해요
      </Label>
      {error ? (
        <p role="alert" className="mt-3 text-sm text-destructive">
          {error}
        </p>
      ) : null}
      <Button
        type="submit"
        disabled={!collection}
        loading={busy}
        loadingText="저장 중"
        className="mt-4"
      >
        문서 저장
      </Button>
    </form>
  );
}
