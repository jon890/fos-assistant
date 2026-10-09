"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
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
import { Notice } from "@/components/ui/notice";
import { describeError, describeFailure } from "@/components/error-message";
import { restorePreviousSkill } from "@/lib/agent-api";
import { formatWhen } from "@/lib/format";

/** 되돌리기에서만 뜻이 정해지는 오류 코드의 문구다. 나머지는 공용 문구를 쓴다. */
const RESTORE_FAILURES: Record<string, string> = {
  SKILL_NOT_FOUND: "되돌릴 이전 버전이 없어요.",
};

/**
 * 올린 스킬의 이전 버전을 남긴 시각과 「이전 버전으로」 단추다.
 *
 * <p>확인을 받으면 서버의 지금 버전과 이전 버전을 맞바꾸고 화면을 새로 읽는다. 편집 화면이 편집기에
 * `previousSavedAt` 을 key 로 주므로, 새로 읽으면 편집기가 맞바꾼 내용으로 다시 그려진다.
 * 요청이 도는 동안 창이 닫히지 않고, 실패하면 창이 남아 까닭을 보인다.
 */
export function SkillRestorePrevious({
  code,
  name,
  previousSavedAt,
}: {
  code: string;
  name: string;
  previousSavedAt: string;
}) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function restore() {
    setBusy(true);
    setError(null);
    try {
      const response = await restorePreviousSkill(code, name);
      if (!response.ok) {
        setError(await describeFailure(response, RESTORE_FAILURES));
        return;
      }
      setOpen(false);
      router.refresh();
    } catch {
      setError(describeError("HERMES_UNAVAILABLE", "연결할 수 없어요."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto mb-4 flex w-full max-w-3xl flex-wrap items-center justify-between gap-2">
      <p className="text-sm text-muted-foreground">
        이전 버전: {formatWhen(previousSavedAt)}
      </p>
      <Button
        size="sm"
        variant="outline"
        onClick={() => {
          setError(null);
          setOpen(true);
        }}
      >
        이전 버전으로
      </Button>
      <AlertDialog
        open={open}
        onOpenChange={(next) => {
          if (!next && !busy) setOpen(false);
        }}
      >
        <AlertDialogContent
          onEscapeKeyDown={(event) => {
            if (busy) event.preventDefault();
          }}
        >
          <AlertDialogHeader>
            <AlertDialogTitle>이전 버전으로 되돌릴까요?</AlertDialogTitle>
            <AlertDialogDescription>
              지금 버전과 이전 버전을 맞바꿔요. 한 번 더 누르면 다시 돌아와요.
              저장하지 않은 편집은 사라져요.
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
            {/* AlertDialogAction 은 누르는 즉시 창을 닫아, 실패해도 창이 사라지므로 일반 Button 으로 둔다. */}
            <Button
              loading={busy}
              loadingText="되돌리는 중"
              onClick={() => void restore()}
            >
              되돌리기
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
