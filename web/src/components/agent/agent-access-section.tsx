"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { VisibilityConfirm } from "@/components/admin/visibility-confirm";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { describeError } from "@/components/error-message";
import { GROUP_VISIBILITY, PRIVATE_VISIBILITY, type AdminAgent } from "@/lib/agent";

type Visibility = AdminAgent["visibility"];

type Props = {
  code: string;
  name: string;
  visibility: Visibility;
  /** 공개 범위를 바꾼 요청이 성공하면 바뀐 값으로 부른다. 그 범위에 따라 달라지는 이웃 절이 받는다. */
  onVisibilityChange(visibility: Visibility): void;
};

type Action = "group" | "private" | "delete";

type ErrorPayload = { code: string; message: string };

async function failureOf(response: Response): Promise<string> {
  try {
    const payload = (await response.json()) as ErrorPayload;
    return describeError(payload.code, payload.message);
  } catch {
    return describeError("INTERNAL_ERROR", "요청을 처리하지 못했어요.");
  }
}

/** 지우기 확인 창이다. `VisibilityConfirm` 과 같이 요청이 도는 동안 닫히지 않고, 실패하면 창이 남아 까닭을 보인다. */
function DeleteConfirm({ name, busy, error, onCancel, onConfirm }: {
  name: string;
  busy: boolean;
  error: string | null;
  onCancel(): void;
  onConfirm(): void;
}) {
  return (
    <AlertDialog open onOpenChange={(open) => { if (!open && !busy) onCancel(); }}>
      <AlertDialogContent onEscapeKeyDown={(event) => { if (busy) event.preventDefault(); }}>
        <AlertDialogHeader>
          <AlertDialogTitle>{name} 에이전트를 지울까요?</AlertDialogTitle>
          <AlertDialogDescription>
            에이전트를 지우면 새 대화를 시작할 수 없어요. 지난 대화는 읽을 수 있어요.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {error ? <p role="alert" className="rounded-md bg-muted p-3 text-sm">{error}</p> : null}
        <AlertDialogFooter>
          {/* AlertDialogCancel 로 두어야 Radix 가 창을 열 때 「취소」 에 초점을 준다. */}
          <AlertDialogCancel asChild>
            <Button variant="outline" disabled={busy}>취소</Button>
          </AlertDialogCancel>
          {/* AlertDialogAction 은 누르는 즉시 창을 닫아, 지우기가 실패해도 창이 사라지므로 일반 Button 으로 둔다. */}
          <Button variant="destructive" loading={busy} loadingText="지우는 중" onClick={onConfirm}>지우기</Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** 에이전트를 관리하는 사람이 공개 범위를 바꾸고 에이전트를 지운다. */
export function AgentAccessSection({ code, name, visibility, onVisibilityChange }: Props) {
  const router = useRouter();
  const [pending, setPending] = useState<Action | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<"group" | "delete" | null>(null);
  const busy = pending !== null;

  async function changeVisibility(next: Visibility, action: Action): Promise<boolean> {
    setPending(action);
    setError(null);
    try {
      const response = await fetch(`/api/agents/${code}/visibility`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ visibility: next }),
      });
      if (!response.ok) {
        setError(await failureOf(response));
        return false;
      }
      const updated = (await response.json()) as { visibility: Visibility };
      onVisibilityChange(updated.visibility);
      return true;
    } catch {
      setError(describeError("HERMES_UNAVAILABLE", "연결할 수 없어요."));
      return false;
    } finally {
      setPending(null);
    }
  }

  async function confirmGroup() {
    if (await changeVisibility(GROUP_VISIBILITY, "group")) setConfirming(null);
  }

  async function confirmDelete() {
    setPending("delete");
    setError(null);
    try {
      const response = await fetch(`/api/agents/${code}`, { method: "DELETE" });
      if (!response.ok) {
        setError(await failureOf(response));
        setPending(null);
        return;
      }
      // 성공하면 목록으로 옮겨 가며 이 절이 사라지므로, 그때까지 창을 진행 중인 채로 둔다.
      router.push("/agents");
    } catch {
      setError(describeError("HERMES_UNAVAILABLE", "연결할 수 없어요."));
      setPending(null);
    }
  }

  function cancel() {
    setConfirming(null);
    setError(null);
  }

  return (
    <section aria-label="공개와 삭제" className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold">공개와 삭제</h2>
          <p className="mt-1 text-sm text-muted-foreground">그룹에 공개하거나 에이전트를 지울 수 있어요.</p>
        </div>
        <Badge variant="outline">{visibility === PRIVATE_VISIBILITY ? "나만" : "그룹 공개"}</Badge>
      </div>
      {error && confirming === null ? <p role="alert" className="mt-4 rounded-md bg-muted p-3 text-sm">{error}</p> : null}
      <div className="mt-4 flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          disabled={busy}
          loading={pending === "private"}
          loadingText="바꾸는 중"
          onClick={() => visibility === PRIVATE_VISIBILITY ? setConfirming("group") : void changeVisibility(PRIVATE_VISIBILITY, "private")}
        >
          {visibility === PRIVATE_VISIBILITY ? "그룹 공개로 변경" : "나만으로 변경"}
        </Button>
        <Button size="sm" variant="ghost" disabled={busy} onClick={() => setConfirming("delete")}>에이전트 지우기</Button>
      </div>
      {confirming === "group" ? (
        <VisibilityConfirm name={name} busy={pending === "group"} error={error} onCancel={cancel} onConfirm={() => void confirmGroup()} />
      ) : null}
      {confirming === "delete" ? (
        <DeleteConfirm name={name} busy={pending === "delete"} error={error} onCancel={cancel} onConfirm={() => void confirmDelete()} />
      ) : null}
    </section>
  );
}
