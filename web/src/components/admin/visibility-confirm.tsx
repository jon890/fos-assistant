import type { AdminAgent } from "@/lib/agent";
import { Button } from "@/components/ui/button";

type Props = {
  agent: AdminAgent;
  busy: boolean;
  onCancel(): void;
  onConfirm(): void;
};

export function VisibilityConfirm({ agent, busy, onCancel, onConfirm }: Props) {
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-foreground/25 p-4">
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="visibility-confirm-title"
        className="w-full max-w-md rounded-md border border-border bg-background p-5 shadow-xl"
      >
        <h2 id="visibility-confirm-title" className="text-lg font-semibold">{agent.name} 에이전트를 가족에게 공개할까요?</h2>
        <p className="mt-3 text-sm leading-6 text-muted">
          모든 사용자가 이 에이전트를 골라 대화할 수 있게 된다. 연결된 도구와 자료를 함께 쓸 수 있는지
          확인한 뒤 공개한다.
        </p>
        <div className="mt-5 flex justify-end gap-2">
          <Button onClick={onCancel} disabled={busy} variant="secondary">
            취소
          </Button>
          <Button onClick={onConfirm} disabled={busy}>
            가족 공개
          </Button>
        </div>
      </section>
    </div>
  );
}
