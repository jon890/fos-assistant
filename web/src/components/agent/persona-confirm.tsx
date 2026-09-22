import { Button } from "@/components/ui/button";

type Props = {
  agentName: string;
  busy: boolean;
  onCancel(): void;
  onConfirm(): void;
};

export function PersonaConfirm({ agentName, busy, onCancel, onConfirm }: Props) {
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-foreground/25 p-4">
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="persona-confirm-title"
        className="w-full max-w-md rounded-md border border-border bg-background p-5 shadow-xl"
      >
        <h2 id="persona-confirm-title" className="text-lg font-semibold">
          {agentName}의 성격을 저장할까요?
        </h2>
        <p className="mt-3 text-sm leading-6 text-muted">
          저장하면 앞의 본문이 사라지고 되돌릴 수 없습니다. 이 성격으로 앞으로의 대화가 답합니다.
        </p>
        <div className="mt-5 flex justify-end gap-2">
          <Button onClick={onCancel} disabled={busy} variant="secondary">
            취소
          </Button>
          <Button onClick={onConfirm} disabled={busy}>
            저장한다
          </Button>
        </div>
      </section>
    </div>
  );
}
