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

/** 서버 비밀값을 바꾸거나 지우기 전의 확인 창이다. 처리하는 동안에는 닫히지 않는다. */
export function SecretConfirmDialog({
  title,
  description,
  confirmLabel,
  busyLabel,
  destructive,
  busy,
  onCancel,
  onConfirm,
}: {
  title: string;
  description: string;
  confirmLabel: string;
  busyLabel: string;
  destructive: boolean;
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
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel asChild>
            <Button variant="outline" disabled={busy}>
              취소
            </Button>
          </AlertDialogCancel>
          <Button
            variant={destructive ? "destructive" : "default"}
            loading={busy}
            loadingText={busyLabel}
            onClick={onConfirm}
          >
            {confirmLabel}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
