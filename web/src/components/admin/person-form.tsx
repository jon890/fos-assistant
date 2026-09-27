import { useId } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

type Props = {
  /** 어느 요청이든 돌고 있다. 그동안 「더하기」 를 잠근다. */
  busy: boolean;
  /** 이 양식의 더하기 요청이 돌고 있다. 「더하기」 에만 회전 표시를 둔다. */
  creating: boolean;
  onCreate(event: React.FormEvent<HTMLFormElement>): void;
};

export function PersonForm({ busy, creating, onCreate }: Props) {
  const id = useId();
  return (
    <form onSubmit={onCreate} className="mb-8 rounded-md border border-border p-4">
      <h2 className="font-semibold">사람 더하기</h2>
      <div className="mt-4 grid gap-4 md:grid-cols-3">
        <div className="grid gap-1.5">
          <Label htmlFor={`${id}-email`}>이메일</Label>
          <Input id={`${id}-email`} name="email" type="email" required autoComplete="off" />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor={`${id}-name`}>이름</Label>
          <Input id={`${id}-name`} name="displayName" required maxLength={100} />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor={`${id}-profile`}>Hermes profile</Label>
          <Input id={`${id}-profile`} name="hermesProfile" required pattern="[a-z0-9][a-z0-9-]{0,63}" />
        </div>
      </div>
      <p className="mt-3 text-xs text-muted-foreground">
        profile 이름은 소문자와 숫자와 붙임표만 쓴다. 더하면 Hermes profile 과 key 가 함께 만들어진다.
      </p>
      <Button type="submit" disabled={busy} loading={creating} loadingText="더하는 중" className="mt-4">
        더하기
      </Button>
    </form>
  );
}
