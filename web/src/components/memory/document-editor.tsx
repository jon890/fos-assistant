"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  updateDocument,
  type MemoryDocument,
  type MemoryDocumentDetail,
} from "@/lib/memory-document";

/**
 * 연 문서의 본문을 고치는 폼이다.
 *
 * <p>판 번호가 달라 거절돼도 폼을 닫지 않는다. 입력 칸에 사용자가 쓴 글이 그대로 남아 있어야 복사해 두고 다시 열 수 있다.
 */
export function DocumentEditor({
  document,
  content,
  onSaved,
  onCancel,
}: {
  document: MemoryDocument;
  content: string;
  onSaved(saved: MemoryDocumentDetail): Promise<void>;
  onCancel(): void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  /** 판 번호가 달라 거절됐다. 쓰던 글이 입력 칸에 남아 있다고 함께 알린다. */
  const [conflict, setConflict] = useState(false);

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError(undefined);
    setConflict(false);
    try {
      const result = await updateDocument(document.id, {
        content: String(form.get("content")),
        sensitive: form.get("sensitive") === "on",
        expectedRevision: document.revision,
      });
      if (!result.ok) {
        setError(result.message);
        setConflict(result.code === "MEMORY_REVISION_CONFLICT");
        return;
      }
      await onSaved(result.data);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={(event) => void save(event)} className="mt-3">
      {error ? (
        <div role="alert" className="mb-2 text-sm text-destructive">
          <p>{error}</p>
          {conflict ? (
            <p>
              쓰던 글은 아래 입력 칸에 그대로 있어요. 복사해 둔 뒤 문서를 다시
              열어 주세요.
            </p>
          ) : null}
        </div>
      ) : null}
      {/* Textarea 의 기본 field-sizing-content 는 rows 를 무시하므로 고정으로 되돌린다. */}
      <Textarea
        name="content"
        defaultValue={content}
        required
        rows={8}
        maxLength={12000}
        aria-label="문서 내용"
        className="field-sizing-fixed"
      />
      <Label className="mt-2 font-normal">
        <input
          name="sensitive"
          type="checkbox"
          defaultChecked={document.sensitive}
          className="accent-primary"
        />
        민감한 내용이에요. 암호화해서 저장해요
      </Label>
      <div className="mt-3 flex gap-2">
        <Button type="submit" size="sm" loading={busy} loadingText="저장 중">
          저장
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={busy}
          onClick={onCancel}
        >
          취소
        </Button>
      </div>
    </form>
  );
}
