type Props = {
  ownerEmail: string;
  busy: boolean;
  onCreate(event: React.FormEvent<HTMLFormElement>): void;
};

const fieldClass = "mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm";

export function AgentForm({ ownerEmail, busy, onCreate }: Props) {
  return (
    <form onSubmit={onCreate} className="mb-8 rounded-lg border border-border p-4">
      <h2 className="font-semibold">에이전트 등록</h2>
      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <label className="text-sm">코드<input name="code" required pattern="[a-z0-9][a-z0-9-]*" className={fieldClass} /></label>
        <label className="text-sm">이름<input name="name" required className={fieldClass} /></label>
        <label className="text-sm">Hermes profile<input name="hermesProfile" required className={fieldClass} /></label>
        <label className="text-sm">Hermes API 주소<input name="apiBaseUrl" required className={fieldClass} /></label>
        <label className="text-sm">provider<input name="provider" required className={fieldClass} /></label>
        <label className="text-sm">개인 소유자 이메일<input name="ownerEmail" defaultValue={ownerEmail} className={fieldClass} /></label>
        <label className="text-sm">비용 방식<select name="costMode" defaultValue="SUBSCRIPTION" className={fieldClass}><option value="SUBSCRIPTION">구독</option><option value="API">API</option></select></label>
        <label className="text-sm">credential 범위<select name="credentialScope" defaultValue="SHARED_HOUSEHOLD" className={fieldClass}><option value="SHARED_HOUSEHOLD">가족 공유 credential</option><option value="DEDICATED">전용 credential</option></select></label>
        <label className="text-sm">공개 범위<select name="visibility" defaultValue="PRIVATE" className={fieldClass}><option value="PRIVATE">나만</option><option value="FAMILY">가족 공개</option></select></label>
      </div>
      <p className="mt-3 text-xs text-muted">모델은 등록할 때 Hermes에서 읽는다.</p>
      <button disabled={busy} className="mt-4 rounded-md bg-foreground px-4 py-2 text-sm text-background disabled:opacity-50">등록</button>
    </form>
  );
}
