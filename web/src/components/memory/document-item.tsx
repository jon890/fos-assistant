"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useExit } from "@/components/ui/use-exit";
import { formatRelative } from "@/lib/format";
import {
  deleteDocument,
  openDocument,
  type MemoryDocument,
  type MemoryDocumentDetail,
} from "@/lib/memory-document";
import { DeleteConfirm } from "./delete-confirm";
import { DocumentEditor } from "./document-editor";
import { ExpandableRow } from "./expandable-row";

/** 문서 한 줄이다. 누르면 펼치면서 본문을 받아 보이고, 고치거나 지운다. */
export function DocumentItem({
  document,
  collectionName,
  open,
  readAt,
  onToggle,
  onChanged,
}: {
  document: MemoryDocument;
  collectionName: string;
  open: boolean;
  readAt: string;
  onToggle(): void;
  onChanged(): Promise<void>;
}) {
  const { leaving, exit } = useExit();
  /**
   * 연 문서다. 펼치기 전과 접은 뒤에는 `null` 이라 화면의 상태에 본문이 남지 않는다.
   * 고칠 때 보내는 판 번호는 목록의 값이 아니라 이 문서를 연 때의 값이다. 목록이 다시 읽혀도 본문과 판이 어긋나지 않는다.
   */
  const [opened, setOpened] = useState<MemoryDocumentDetail | null>(null);
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [pending, setPending] = useState<"open" | "remove" | null>(null);
  const [error, setError] = useState<string>();

  // 다른 줄을 펼쳐 이 줄이 접히면 받은 본문을 버린다.
  if (!open && (opened !== null || editing)) {
    setOpened(null);
    setEditing(false);
  }

  async function load() {
    setPending("open");
    setError(undefined);
    const result = await openDocument(document.id);
    setPending(null);
    if (result.ok) setOpened(result.data);
    else setError(result.message);
  }

  async function remove() {
    setPending("remove");
    setError(undefined);
    const result = await deleteDocument(document.id);
    setPending(null);
    setConfirming(false);
    if (!result.ok) {
      setError(result.message);
      return;
    }
    // 요청이 성공한 뒤에만 줄을 흐리게 하고 목록을 다시 읽는다.
    exit(onChanged);
  }

  function toggle() {
    setError(undefined);
    if (!open) void load();
    onToggle();
  }

  const busy = pending !== null;
  return (
    <ExpandableRow
      title={document.title}
      meta={
        <>
          <Badge variant="outline">{collectionName}</Badge>
          <span>
            <span aria-hidden>· </span>
            {formatRelative(document.updatedAt, new Date(readAt))}
          </span>
          {document.sensitive ? <Badge variant="warning">민감</Badge> : null}
        </>
      }
      open={open}
      onToggle={toggle}
      leaving={leaving}
    >
      <p className="break-all text-xs text-muted-foreground">
        {document.documentKey} · {document.revision}번째 판
      </p>
      {error ? (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {error}
        </p>
      ) : null}
      {pending === "open" ? (
        <p className="mt-3 text-sm text-muted-foreground">여는 중이에요</p>
      ) : null}
      {opened !== null && editing ? (
        <DocumentEditor
          opened={opened}
          onCancel={() => setEditing(false)}
          onSaved={async (saved) => {
            setOpened(saved);
            setEditing(false);
            await onChanged();
          }}
        />
      ) : (
        <>
          {opened !== null ? (
            <pre className="mt-3 max-w-full whitespace-pre-wrap break-words font-sans text-sm">
              {opened.content}
            </pre>
          ) : null}
          <div className="mt-3 flex gap-2">
            {opened !== null ? (
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
            ) : null}
            <Button
              size="sm"
              variant="destructive"
              disabled={busy}
              onClick={() => setConfirming(true)}
            >
              지우기
            </Button>
          </div>
        </>
      )}
      {confirming ? (
        <DeleteConfirm
          title="이 문서를 지울까요?"
          description="지운 문서는 에이전트와 외부 서비스가 더 이상 찾지 못하고 되살릴 수 없어요."
          busy={pending === "remove"}
          onConfirm={() => void remove()}
          onClose={() => setConfirming(false)}
        />
      ) : null}
    </ExpandableRow>
  );
}
