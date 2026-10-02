"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useExit } from "@/components/ui/use-exit";
import {
  deleteDocument,
  openDocument,
  type MemoryDocument,
} from "@/lib/memory-document";
import { DocumentEditor } from "./document-editor";

export function DocumentItem({
  document,
  collectionName,
  onChanged,
}: {
  document: MemoryDocument;
  collectionName: string;
  onChanged(): Promise<void>;
}) {
  const { leaving, exit } = useExit();
  /** 연 문서의 본문이다. 열기 전과 닫은 뒤에는 `null` 이라 화면의 상태에 본문이 남지 않는다. */
  const [content, setContent] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [pending, setPending] = useState<"open" | "remove" | null>(null);
  const [error, setError] = useState<string>();

  async function open() {
    setPending("open");
    setError(undefined);
    const result = await openDocument(document.id);
    setPending(null);
    if (result.ok) setContent(result.data.content);
    else setError(result.message);
  }

  async function remove() {
    setPending("remove");
    setError(undefined);
    const result = await deleteDocument(document.id);
    setPending(null);
    if (!result.ok) {
      setError(result.message);
      return;
    }
    // 요청이 성공한 뒤에만 줄을 흐리게 하고 목록을 다시 읽는다.
    exit(onChanged);
  }

  function close() {
    setContent(null);
    setEditing(false);
    setError(undefined);
  }

  const busy = pending !== null;
  return (
    <article
      data-leaving={leaving || undefined}
      className="rounded-md border border-border p-4"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="min-w-0 break-words font-semibold">{document.title}</h3>
        {document.sensitive ? <Badge variant="warning">민감</Badge> : null}
      </div>
      <p className="mt-1 break-all text-xs text-muted-foreground">
        {collectionName} · {document.documentKey} · {document.revision}번째 판
      </p>
      {error ? (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {error}
        </p>
      ) : null}
      {content !== null && editing ? (
        <DocumentEditor
          document={document}
          content={content}
          onCancel={() => setEditing(false)}
          onSaved={async (saved) => {
            setContent(saved.content);
            setEditing(false);
            await onChanged();
          }}
        />
      ) : (
        <>
          {content !== null ? (
            <pre className="mt-3 max-w-full whitespace-pre-wrap break-words font-sans text-sm">
              {content}
            </pre>
          ) : null}
          <div className="mt-3 flex gap-2">
            {content === null ? (
              <Button
                size="sm"
                variant="outline"
                disabled={busy}
                loading={pending === "open"}
                loadingText="여는 중"
                onClick={() => void open()}
              >
                열기
              </Button>
            ) : (
              <>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy}
                  onClick={close}
                >
                  닫기
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy}
                  onClick={() => {
                    setError(undefined);
                    setEditing(true);
                  }}
                >
                  고치기
                </Button>
              </>
            )}
            <Button
              size="sm"
              variant="destructive"
              disabled={busy}
              loading={pending === "remove"}
              loadingText="지우는 중"
              onClick={() => void remove()}
            >
              지우기
            </Button>
          </div>
        </>
      )}
    </article>
  );
}
