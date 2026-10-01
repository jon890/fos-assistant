"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { useExit } from "@/components/ui/use-exit";
import { cn } from "cn";
import type { Memory } from "./memory-list";

export function MemoryItem({
  memory,
  canEdit,
  entering = false,
  onChanged,
}: {
  memory: Memory;
  canEdit: boolean;
  /** 화면을 연 뒤에 생긴 줄이다. 등장 움직임을 준다 */
  entering?: boolean;
  onChanged(): Promise<void>;
}) {
  const { leaving, exit } = useExit();
  const [editing, setEditing] = useState(false);
  /** 보내는 중인 요청이다. 누른 단추에만 회전 표시를 두려고 어느 쪽인지 기억한다. */
  const [pending, setPending] = useState<"update" | "remove" | null>(null);
  const [error, setError] = useState<string>();

  async function update(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setPending("update");
    setError(undefined);
    let response: Response;
    try {
      response = await fetch(`/api/memories/${memory.id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          content: form.get("content"),
          alwaysInject: form.get("alwaysInject") === "on",
        }),
      });
    } catch {
      setError("기억을 고치지 못했어요.");
      return;
    } finally {
      setPending(null);
    }
    if (!response.ok) {
      setError("기억을 고치지 못했어요.");
      return;
    }
    setEditing(false);
    await onChanged();
  }
  async function remove() {
    setPending("remove");
    setError(undefined);
    let response: Response;
    try {
      response = await fetch(`/api/memories/${memory.id}`, {
        method: "DELETE",
      });
    } catch {
      setError("기억을 지우지 못했어요.");
      return;
    } finally {
      setPending(null);
    }
    if (!response.ok) {
      setError("기억을 지우지 못했어요.");
      return;
    }
    // 요청이 성공한 뒤에만 줄을 흐리게 하고 목록을 다시 읽는다.
    exit(() => void onChanged());
  }

  return (
    <article
      data-leaving={leaving || undefined}
      className={cn(
        "rounded-md border border-border p-4",
        entering && "animate-message-assistant",
      )}
    >
      <h3 className="font-semibold">{memory.title}</h3>
      {memory.omittedFromContext ? (
        <Badge
          variant="destructive"
          className="mt-1"
          data-testid="memory-omitted"
        >
          길어서 답에 포함되지 않음
        </Badge>
      ) : null}
      {error ? (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {error}
        </p>
      ) : null}
      {/* Textarea 의 기본 field-sizing-content 는 rows 를 무시하므로 고정으로 되돌린다. */}
      {editing && canEdit ? (
        <form onSubmit={(event) => void update(event)} className="mt-3">
          <Textarea
            name="content"
            defaultValue={memory.content}
            required
            rows={3}
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
              disabled={pending !== null}
              loading={pending === "update"}
              loadingText="저장 중"
            >
              저장
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => setEditing(false)}
            >
              취소
            </Button>
          </div>
        </form>
      ) : (
        <>
          <p className="mt-2 whitespace-pre-wrap text-sm">{memory.content}</p>
          <Badge variant="outline" className="mt-2">
            {memory.alwaysInject
              ? "답을 만들 때 항상 함께 넣음"
              : "필요할 때 제목만 넣음"}
          </Badge>
          {canEdit ? (
            <div className="mt-3 flex gap-2">
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
              <Button
                size="sm"
                variant="destructive"
                disabled={pending !== null}
                loading={pending === "remove"}
                loadingText="지우는 중"
                onClick={() => void remove()}
              >
                지우기
              </Button>
            </div>
          ) : null}
        </>
      )}
    </article>
  );
}
