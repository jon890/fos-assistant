"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useExit } from "@/components/ui/use-exit";
import { formatFullTime } from "@/lib/format";
import { deleteMemory } from "@/lib/memory-api";
import { DeleteConfirm } from "./delete-confirm";
import { ExpandableRow } from "./expandable-row";
import { MemoryEditForm } from "./memory-edit-form";
import { MemoryMeta } from "./memory-meta";
import type { Memory } from "./memory-list";

/** 받아들인 기억 한 줄이다. 누르면 펼쳐 본문을 보이고 고치거나 지운다. */
export function MemoryItem({
  memory,
  canEdit,
  open,
  readAt,
  entering = false,
  onToggle,
  onChanged,
}: {
  memory: Memory;
  canEdit: boolean;
  open: boolean;
  readAt: string;
  /** 화면을 연 뒤에 생긴 줄이다. 등장 움직임을 준다 */
  entering?: boolean;
  onToggle(): void;
  onChanged(): Promise<void>;
}) {
  const { leaving, exit } = useExit();
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [error, setError] = useState<string>();

  async function remove() {
    setRemoving(true);
    setError(undefined);
    let response: Response;
    try {
      response = await deleteMemory(memory.id);
    } catch {
      setError("기억을 지우지 못했어요.");
      setConfirming(false);
      return;
    } finally {
      setRemoving(false);
    }
    setConfirming(false);
    if (!response.ok) {
      setError("기억을 지우지 못했어요.");
      return;
    }
    // 요청이 성공한 뒤에만 줄을 흐리게 하고 목록을 다시 읽는다. 다시 읽기가 끝나야 줄의 흐림이 풀린다.
    exit(onChanged);
  }

  // 민감한 기억은 본문이 오지 않는다. 빈 칸으로 덮어쓰지 않게 고치기를 보이지 않는다.
  const editable = canEdit && !memory.sensitive;
  return (
    <ExpandableRow
      title={memory.title}
      meta={<MemoryMeta memory={memory} readAt={readAt} />}
      open={open}
      onToggle={() => {
        setEditing(false);
        setError(undefined);
        onToggle();
      }}
      leaving={leaving}
      entering={entering}
    >
      {error ? (
        <p role="alert" className="mb-2 text-sm text-destructive">
          {error}
        </p>
      ) : null}
      {editing && editable ? (
        <MemoryEditForm
          memory={memory}
          onCancel={() => setEditing(false)}
          onSaved={async () => {
            setEditing(false);
            await onChanged();
          }}
        />
      ) : (
        <>
          {memory.sensitive ? (
            <p className="text-sm text-muted-foreground">
              민감한 내용이라 여기서는 보이지 않아요.
            </p>
          ) : (
            <p className="whitespace-pre-wrap break-words text-sm">
              {memory.content}
            </p>
          )}
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Badge variant="outline">
              {memory.alwaysInject
                ? "답을 만들 때 항상 함께 넣음"
                : "필요할 때 제목만 넣음"}
            </Badge>
            {memory.updatedAt ? (
              <span className="text-xs text-muted-foreground">
                {formatFullTime(memory.updatedAt)}에 바뀜
              </span>
            ) : null}
          </div>
          {canEdit ? (
            <div className="mt-3 flex gap-2">
              {editable ? (
                <Button
                  size="sm"
                  variant="outline"
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
                disabled={removing}
                onClick={() => setConfirming(true)}
              >
                지우기
              </Button>
            </div>
          ) : null}
        </>
      )}
      {confirming ? (
        <DeleteConfirm
          title="이 기억을 지울까요?"
          description="지운 기억은 다음 대화부터 쓰이지 않고 되살릴 수 없어요."
          busy={removing}
          onConfirm={() => void remove()}
          onClose={() => setConfirming(false)}
        />
      ) : null}
    </ExpandableRow>
  );
}
