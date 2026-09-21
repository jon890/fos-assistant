import { Button } from "@/components/ui/button";

type Props = {
  busy: boolean;
  onCreate(event: React.FormEvent<HTMLFormElement>): void;
};

const fieldClass = "mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm";

export function PersonForm({ busy, onCreate }: Props) {
  return (
    <form onSubmit={onCreate} className="mb-8 rounded-md border border-border p-4">
      <h2 className="font-semibold">사람 더하기</h2>
      <div className="mt-4 grid gap-4 md:grid-cols-3">
        <label className="text-sm">
          이메일
          <input name="email" type="email" required autoComplete="off" className={fieldClass} />
        </label>
        <label className="text-sm">
          이름
          <input name="displayName" required maxLength={100} className={fieldClass} />
        </label>
        <label className="text-sm">
          Hermes profile
          <input
            name="hermesProfile"
            required
            pattern="[a-z0-9][a-z0-9-]{0,63}"
            className={fieldClass}
          />
        </label>
      </div>
      <p className="mt-3 text-xs text-muted">
        profile 이름은 소문자와 숫자와 붙임표만 쓴다. 더하면 Hermes profile 과 key 가 함께 만들어진다.
      </p>
      <Button type="submit" disabled={busy} className="mt-4">
        {busy ? "더하는 중" : "더하기"}
      </Button>
    </form>
  );
}
