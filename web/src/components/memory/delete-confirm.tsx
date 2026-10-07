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

/** 기억과 문서를 지우기 전에 한 번 더 묻는 확인 창이다. 지우는 중에는 닫히지 않는다. */
export function DeleteConfirm({
  title,
  description,
  busy,
  onConfirm,
  onClose,
}: {
  title: string;
  description: string;
  busy: boolean;
  onConfirm(): void;
  onClose(): void;
}) {
  return (
    <AlertDialog
      open
      onOpenChange={(open) => {
        if (!open && !busy) onClose();
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
