import { useId } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";

type Props = {
  ownerEmail: string;
  /** 어느 요청이든 돌고 있다. 그동안 「등록」 을 잠근다. */
  busy: boolean;
  /** 이 양식의 등록 요청이 돌고 있다. 「등록」 에만 회전 표시를 둔다. */
  creating: boolean;
  onCreate(event: React.FormEvent<HTMLFormElement>): void;
};

export function AgentForm({ ownerEmail, busy, creating, onCreate }: Props) {
  const id = useId();
  return (
    <form onSubmit={onCreate} className="mb-8 rounded-md border border-border p-4">
      <h2 className="font-semibold">에이전트 등록</h2>
      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <div className="grid gap-1.5"><Label htmlFor={`${id}-code`}>코드</Label><Input id={`${id}-code`} name="code" required pattern="[a-z0-9][a-z0-9-]*" /></div>
        <div className="grid gap-1.5"><Label htmlFor={`${id}-name`}>이름</Label><Input id={`${id}-name`} name="name" required /></div>
        <div className="grid gap-1.5"><Label htmlFor={`${id}-profile`}>Hermes profile</Label><Input id={`${id}-profile`} name="hermesProfile" required /></div>
        <div className="grid gap-1.5"><Label htmlFor={`${id}-address`}>Hermes API 주소</Label><Input id={`${id}-address`} name="apiBaseUrl" required /></div>
        <div className="grid gap-1.5"><Label htmlFor={`${id}-provider`}>provider</Label><Input id={`${id}-provider`} name="provider" required /></div>
        <div className="grid gap-1.5"><Label htmlFor={`${id}-owner`}>개인 소유자 이메일</Label><Input id={`${id}-owner`} name="ownerEmail" defaultValue={ownerEmail} /></div>
        <div className="grid gap-1.5"><Label htmlFor={`${id}-cost`}>비용 방식</Label><NativeSelect id={`${id}-cost`} name="costMode" defaultValue="SUBSCRIPTION"><option value="SUBSCRIPTION">구독</option><option value="API">API</option></NativeSelect></div>
        <div className="grid gap-1.5"><Label htmlFor={`${id}-credential`}>credential 범위</Label><NativeSelect id={`${id}-credential`} name="credentialScope" defaultValue="SHARED_HOUSEHOLD"><option value="SHARED_HOUSEHOLD">가족 공유 credential</option><option value="DEDICATED">전용 credential</option></NativeSelect></div>
        <div className="grid gap-1.5"><Label htmlFor={`${id}-visibility`}>공개 범위</Label><NativeSelect id={`${id}-visibility`} name="visibility" defaultValue="PRIVATE"><option value="PRIVATE">나만</option><option value="GROUP">그룹 공개</option></NativeSelect></div>
      </div>
      <p className="mt-3 text-xs text-muted-foreground">모델은 등록할 때 Hermes에서 읽는다.</p>
      <Button type="submit" disabled={busy} loading={creating} loadingText="등록 중" className="mt-4">등록</Button>
    </form>
  );
}
