"use client";

import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { withParticle } from "@/lib/korean-particle";
import type { WorkspaceEntryKind } from "@/lib/workspace-file";

const KIND_LABELS: Record<WorkspaceEntryKind, string> = {
  DIRECTORY: "폴더",
  FILE: "파일",
  LINK: "링크",
  OTHER: "특수 파일",
};

/** 파일 공간의 항목 하나를 지우는 확인 창이다. 지우는 동안에는 닫히지 않는다. */
export function DeleteEntryDialog({
  name,
  kind,
  running,
  busy,
  onCancel,
  onConfirm,
}: {
  name: string;
  kind: WorkspaceEntryKind;
  /** 창을 열 때 다시 읽은 상태에서 에이전트 실행이 돌고 있었는가. 읽지 못했으면 거짓이다. */
  running: boolean;
  busy: boolean;
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
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle className="break-all">
            {withParticle(name, "을", "를")} 지울까요?
          </AlertDialogTitle>
          {/* 설명은 창의 aria-describedby 가 하나만 가리키므로 문단들을 한 요소로 묶는다. */}
          <AlertDialogDescription asChild>
            <div className="space-y-1">
              <p>
                {withParticle(KIND_LABELS[kind], "이에요.", "예요.")}
                {kind === "DIRECTORY" ? " 안의 파일까지 모두 지워요." : ""}
              </p>
              {running ? (
                <p>
                  에이전트가 지금 일하고 있어요. 쓰는 중인 파일이면 다시 생길 수
                  있어요.
                </p>
              ) : null}
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel asChild>
            <Button variant="outline" disabled={busy}>
              취소
            </Button>
          </AlertDialogCancel>
          <Button
            variant="destructive"
            loading={busy}
            loadingText="지우는 중…"
            onClick={onConfirm}
          >
            지우기
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
