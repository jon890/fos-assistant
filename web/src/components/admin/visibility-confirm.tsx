import type { AdminAgent } from "@/lib/agent";
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
  agent: AdminAgent;
  busy: boolean;
  onCancel(): void;
  onConfirm(): void;
};

/**
 * 부르는 쪽이 확인할 때만 그리므로 언제나 열린 채로 그린다. `Esc` 와 「취소」 는 모두 `onCancel` 로 간다.
 * 공개 요청이 도는 동안에는 닫히지 않고, 실패하면 부르는 쪽이 창을 남겨 둔다.
 */
export function VisibilityConfirm({ agent, busy, onCancel, onConfirm }: Props) {
  return (
    <AlertDialog open onOpenChange={(open) => { if (!open && !busy) onCancel(); }}>
      <AlertDialogContent onEscapeKeyDown={(event) => { if (busy) event.preventDefault(); }}>
        <AlertDialogHeader>
          <AlertDialogTitle>{agent.name} 에이전트를 그룹에 공개할까요?</AlertDialogTitle>
          <AlertDialogDescription>
            그룹의 모든 사용자가 이 에이전트로 대화할 수 있어요. 연결된 도구와 자료를 함께 써도 되는지
            확인한 뒤 공개해 주세요.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          {/* AlertDialogCancel 로 두어야 Radix 가 창을 열 때 「취소」 에 초점을 준다. */}
          <AlertDialogCancel asChild>
            <Button variant="outline" disabled={busy}>취소</Button>
          </AlertDialogCancel>
          {/* AlertDialogAction 은 누르는 즉시 창을 닫아, 공개가 실패해도 창이 사라지므로 일반 Button 으로 둔다. */}
          <Button loading={busy} loadingText="공개하는 중" onClick={onConfirm}>그룹 공개</Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
