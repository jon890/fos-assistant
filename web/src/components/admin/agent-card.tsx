import { Badge } from "@/components/ui/badge";
import { PRIVATE_VISIBILITY, type AdminAgent } from "@/lib/agent";
import { formatWhen } from "@/lib/format";

type Props = {
  agent: AdminAgent;
  busy: boolean;
  onVisibilityChange(agent: AdminAgent): void;
  onEnabledChange(agent: AdminAgent): void;
  onSyncModel(agent: AdminAgent): void;
};

export function AgentCard({ agent, busy, onVisibilityChange, onEnabledChange, onSyncModel }: Props) {
  return (
    <article className="rounded-lg border border-border p-4">
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
        <div><dt className="text-xs text-muted">Hermes API 주소</dt><dd className="mt-1 break-all">{agent.apiBaseUrl}</dd></div>
        <div><dt className="text-xs text-muted">provider / 모델</dt><dd className="mt-1 break-all">{agent.provider} / {agent.model}</dd></div>
        <div><dt className="text-xs text-muted">모델을 마지막으로 읽은 시각</dt><dd className="mt-1">{agent.modelSyncedAt ? formatWhen(agent.modelSyncedAt) : "-"}</dd></div>
      </dl>
      <div className="mt-4 flex flex-wrap gap-2">
        <button type="button" disabled={busy} onClick={() => onVisibilityChange(agent)} className="rounded-md border border-border px-3 py-2 text-sm disabled:opacity-50">
          {agent.visibility === PRIVATE_VISIBILITY ? "가족 공개로 변경" : "나만으로 변경"}
        </button>
        <button type="button" disabled={busy} onClick={() => onEnabledChange(agent)} className="rounded-md border border-border px-3 py-2 text-sm disabled:opacity-50">
          {agent.enabled ? "사용 중지" : "다시 사용"}
        </button>
        <button type="button" disabled={busy} onClick={() => onSyncModel(agent)} className="rounded-md border border-border px-3 py-2 text-sm disabled:opacity-50">
          모델 다시 읽기
        </button>
      </div>
    </article>
  );
}
