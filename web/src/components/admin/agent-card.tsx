import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { PRIVATE_VISIBILITY, type AdminAgent } from "@/lib/agent";
import { formatWhen } from "@/lib/format";

type Props = {
  agent: AdminAgent;
  busy: boolean;
  onVisibilityChange(agent: AdminAgent): void;
  onEnabledChange(agent: AdminAgent): void;
  onSyncModel(agent: AdminAgent): void;
  /** 주소를 바꾼다. 실패하면 그 이유를, 저장했으면 null 을 돌려준다 */
  onApiBaseUrlChange(agent: AdminAgent, apiBaseUrl: string): Promise<string | null>;
};

export function AgentCard({
  agent,
  busy,
  onVisibilityChange,
  onEnabledChange,
  onSyncModel,
  onApiBaseUrlChange,
}: Props) {
  const [addressError, setAddressError] = useState<string | null>(null);

  async function submitAddress(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setAddressError(await onApiBaseUrlChange(agent, String(form.get("apiBaseUrl") ?? "")));
  }

  return (
    <article className="rounded-md border border-border p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold">{agent.name}</h2>
          <p className="text-xs text-muted">{agent.code}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Badge>{agent.visibility === PRIVATE_VISIBILITY ? "나만" : "가족 공개"}</Badge>
          <Badge emphasis={!agent.enabled}>{agent.enabled ? "사용 중" : "사용 중지"}</Badge>
        </div>
      </div>
      <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-2">
        <div><dt className="text-xs text-muted">Hermes profile</dt><dd className="mt-1 break-all">{agent.hermesProfile}</dd></div>
        <div><dt className="text-xs text-muted">provider / 모델</dt><dd className="mt-1 break-all">{agent.provider} / {agent.model}</dd></div>
        <div><dt className="text-xs text-muted">모델을 마지막으로 읽은 시각</dt><dd className="mt-1">{agent.modelSyncedAt ? formatWhen(agent.modelSyncedAt) : "-"}</dd></div>
      </dl>
      <form onSubmit={(event) => void submitAddress(event)} className="mt-4">
        <label className="text-sm">
          Hermes API 주소
          <input
            name="apiBaseUrl"
            // 지금 값이 바뀌면 다시 그려 새 값을 보인다. 빈 칸으로 두면 무엇이 바뀌는지 알 수 없다.
            key={agent.apiBaseUrl}
            defaultValue={agent.apiBaseUrl}
            aria-label={`${agent.name} Hermes API 주소`}
            className="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
          />
        </label>
        <p className="mt-2 text-xs text-muted">저장하기 전에 이 주소가 응답하는지 확인한다.</p>
        {addressError ? (
          <p role="alert" className="mt-2 rounded-md bg-surface p-3 text-sm break-all">{addressError}</p>
        ) : null}
        <Button type="submit" size="sm" variant="secondary" disabled={busy} className="mt-2">
          주소 저장
        </Button>
      </form>
      <div className="mt-4 flex flex-wrap gap-2">
        <Button size="sm" variant="secondary" disabled={busy} onClick={() => onVisibilityChange(agent)}>
          {agent.visibility === PRIVATE_VISIBILITY ? "가족 공개로 변경" : "나만으로 변경"}
        </Button>
        <Button size="sm" variant="ghost" disabled={busy} onClick={() => onEnabledChange(agent)}>
          {agent.enabled ? "사용 중지" : "다시 사용"}
        </Button>
        <Button size="sm" variant="secondary" disabled={busy} onClick={() => onSyncModel(agent)}>
          모델 다시 읽기
        </Button>
      </div>
    </article>
  );
}
