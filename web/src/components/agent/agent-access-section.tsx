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
import { Notice } from "@/components/ui/notice";
import { describeError, describeFailure } from "@/components/error-message";
import {
  GROUP_VISIBILITY,
  PRIVATE_VISIBILITY,
  type AdminAgent,
} from "@/lib/agent";
import { changeAgentVisibility, deleteAgent } from "@/lib/agent-api";

type Visibility = AdminAgent["visibility"];

type Props = {
  code: string;
  name: string;
  visibility: Visibility;
  /** 에이전트를 지운 뒤 돌아갈 목록 주소다. 일반 화면과 관리자 영역의 목록이 다르다. */
  listHref: string;
  /** 공개 범위를 바꾼 요청이 성공하면 바뀐 값으로 부른다. 그 범위에 따라 달라지는 이웃 절이 받는다. */
  onVisibilityChange(visibility: Visibility): void;
  /** 예전 방식의 연결 에이전트다. 공개 범위는 바꾸지 못하고 지우기만 한다. */
  connectorManaged?: boolean;
};

type Action = "group" | "private" | "delete";

/** 이 절에서만 뜻이 정해지는 오류 코드의 문구다. 나머지는 공용 문구를 쓴다. */
const ACCESS_FAILURES: Record<string, string> = {
  FORBIDDEN: "이 에이전트를 관리할 수 없어요.",
  VALIDATION_FAILED: "공개 범위를 바꾸지 못했어요. 다시 시도해 주세요.",
  AGENT_CONNECTIONS_REQUIRE_PRIVATE:
    "연결이 붙은 에이전트는 그룹에 공개할 수 없어요. 연결을 뗀 뒤 다시 시도해 주세요.",
};

/** 지우기의 문구다. profile 을 거두다 실패한 코드가 사람 추가 화면의 공용 문구로 보이지 않게 따로 둔다. */
const DELETE_FAILURES: Record<string, string> = {
  FORBIDDEN: ACCESS_FAILURES.FORBIDDEN!,
  VALIDATION_FAILED: "에이전트를 지우지 못했어요. 다시 시도해 주세요.",
  HERMES_PROVISION_FAILED: "에이전트를 지우지 못했어요. 다시 시도해 주세요.",
};

/** 지우기 확인 창이다. `VisibilityConfirm` 과 같이 요청이 도는 동안 닫히지 않고, 실패하면 창이 남아 까닭을 보인다. */
function DeleteConfirm({
  name,
  busy,
  error,
  onCancel,
  onConfirm,
}: {
  name: string;
  busy: boolean;
  error: string | null;
  onCancel(): void;
  onConfirm(): void;
}) {
  return (
    <AlertDialog
      open
      onOpenChange={(open) => {
        if (!open && !busy) onCancel();
      }}
    >
      <AlertDialogContent
        onEscapeKeyDown={(event) => {
          if (busy) event.preventDefault();
        }}
      >
        <AlertDialogHeader>
          <AlertDialogTitle>{name} 에이전트를 지울까요?</AlertDialogTitle>
          <AlertDialogDescription>
            에이전트를 지우면 새 대화를 시작할 수 없어요. 지난 대화는 읽을 수
            있어요.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {error ? (
          <Notice variant="error" role="alert">
            {error}
          </Notice>
        ) : null}
        <AlertDialogFooter>
          {/* AlertDialogCancel 로 두어야 Radix 가 창을 열 때 「취소」 에 초점을 준다. */}
          <AlertDialogCancel asChild>
            <Button variant="outline" disabled={busy}>
              취소
            </Button>
          </AlertDialogCancel>
          {/* AlertDialogAction 은 누르는 즉시 창을 닫아, 지우기가 실패해도 창이 사라지므로 일반 Button 으로 둔다. */}
          <Button
            variant="destructive"
            loading={busy}
            loadingText="지우는 중"
            onClick={onConfirm}
          >
            지우기
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** 에이전트를 관리하는 사람이 공개 범위를 바꾸고 에이전트를 지운다. */
export function AgentAccessSection({
  code,
  name,
  visibility,
  listHref,
  onVisibilityChange,
  connectorManaged = false,
}: Props) {
  const router = useRouter();
  const [pending, setPending] = useState<Action | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<"group" | "delete" | null>(null);
  const busy = pending !== null;

  async function changeVisibility(
    next: Visibility,
    action: Action,
  ): Promise<boolean> {
    setPending(action);
    setError(null);
    try {
      const response = await changeAgentVisibility(code, next);
      if (!response.ok) {
        setError(await describeFailure(response, ACCESS_FAILURES));
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
      const response = await deleteAgent(code);
      if (!response.ok) {
        setError(await describeFailure(response, DELETE_FAILURES));
        setPending(null);
        return;
      }
      // 성공하면 목록으로 옮겨 가며 이 절이 사라지므로, 그때까지 창을 진행 중인 채로 둔다.
      router.push(listHref);
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
    <section
      aria-label="공개와 삭제"
      className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold">공개와 삭제</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            {connectorManaged
              ? "이 에이전트를 지울 수 있어요."
              : "그룹에 공개하거나 에이전트를 지울 수 있어요."}
          </p>
        </div>
        <Badge variant="outline">
          {visibility === PRIVATE_VISIBILITY ? "나만" : "그룹 공개"}
        </Badge>
      </div>
      {error && confirming === null ? (
        <Notice variant="error" role="alert" className="mt-4">
          {error}
        </Notice>
      ) : null}
      <div className="mt-4 flex flex-wrap gap-2">
        {connectorManaged ? null : (
          <Button
            size="sm"
            variant="outline"
            disabled={busy}
            loading={pending === "private"}
            loadingText="바꾸는 중"
            onClick={() =>
              visibility === PRIVATE_VISIBILITY
                ? setConfirming("group")
                : void changeVisibility(PRIVATE_VISIBILITY, "private")
            }
          >
            {visibility === PRIVATE_VISIBILITY
              ? "그룹 공개로 변경"
              : "나만으로 변경"}
          </Button>
        )}
        <Button
          size="sm"
          variant="ghost"
          disabled={busy}
          onClick={() => setConfirming("delete")}
        >
          에이전트 지우기
        </Button>
      </div>
      {confirming === "group" ? (
        <VisibilityConfirm
          name={name}
          busy={pending === "group"}
          error={error}
          onCancel={cancel}
          onConfirm={() => void confirmGroup()}
        />
      ) : null}
      {confirming === "delete" ? (
        <DeleteConfirm
          name={name}
          busy={pending === "delete"}
          error={error}
          onCancel={cancel}
          onConfirm={() => void confirmDelete()}
        />
      ) : null}
    </section>
  );
}
