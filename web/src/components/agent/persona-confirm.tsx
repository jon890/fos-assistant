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

type Props = {
  agentName: string;
  busy: boolean;
  onCancel(): void;
  onConfirm(): void;
};

/** 부르는 쪽이 확인할 때만 그리므로 언제나 열린 채로 그린다. `Esc` 와 「취소」 는 모두 `onCancel` 로 간다. */
export function PersonaConfirm({ agentName, busy, onCancel, onConfirm }: Props) {
  return (
    <AlertDialog open onOpenChange={(open) => { if (!open && !busy) onCancel(); }}>
      <AlertDialogContent onEscapeKeyDown={(event) => { if (busy) event.preventDefault(); }}>
        <AlertDialogHeader>
          <AlertDialogTitle>{agentName}의 성격을 저장할까요?</AlertDialogTitle>
          <AlertDialogDescription>
            저장하면 앞의 본문이 사라지고 되돌릴 수 없습니다. 이 성격으로 앞으로의 대화가 답합니다.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          {/* AlertDialogCancel 로 두어야 Radix 가 창을 열 때 「취소」 에 초점을 준다. */}
          <AlertDialogCancel asChild>
            <Button variant="outline" disabled={busy}>취소</Button>
          </AlertDialogCancel>
          {/* AlertDialogAction 은 누르는 즉시 창을 닫고 loading 을 받지 못해 일반 Button 으로 둔다. */}
          <Button loading={busy} loadingText="저장 중" onClick={onConfirm}>저장한다</Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
