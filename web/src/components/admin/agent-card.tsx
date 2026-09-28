import { useId, useState } from "react";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { PRIVATE_VISIBILITY, type AdminAgent } from "@/lib/agent";
import { formatWhen } from "@/lib/format";
import { AgentModelList } from "./agent-model-list";

/**
 * 에이전트 하나에 보내는 요청이다. 도는 요청의 단추에만 회전 표시를 두려고 어느 것인지 가린다.
 * `group` 은 카드가 아니라 공개 확인 창이 보낸다.
 */
export type AgentAction = "private" | "group" | "enabled" | "address" | "sync" | "models";

type Props = {
  agent: AdminAgent;
  /** 어느 에이전트든 요청이 돌고 있다. 그동안 모든 단추를 잠근다. */
  busy: boolean;
  /** 이 에이전트에 대해 도는 요청이다. 없으면 null 이다. */
  pendingAction: AgentAction | null;
  onVisibilityChange(agent: AdminAgent): void;
  onEnabledChange(agent: AdminAgent): void;
  onSyncModel(agent: AdminAgent): void;
  onSaveModels(agent: AdminAgent, models: { provider: string; model: string }[]): void;
  /** 주소를 바꾼다. 실패하면 그 이유를, 저장했으면 null 을 돌려준다 */
  onApiBaseUrlChange(agent: AdminAgent, apiBaseUrl: string): Promise<string | null>;
};

export function AgentCard({
  agent,
  busy,
  pendingAction,
  onVisibilityChange,
  onEnabledChange,
  onSyncModel,
  onSaveModels,
  onApiBaseUrlChange,
}: Props) {
  const [addressError, setAddressError] = useState<string | null>(null);
  const addressId = useId();

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
          <p className="text-xs text-muted-foreground">{agent.code}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Badge variant="outline">{agent.visibility === PRIVATE_VISIBILITY ? "나만" : "그룹 공개"}</Badge>
          <Badge variant={agent.enabled ? "outline" : "default"}>{agent.enabled ? "사용 중" : "사용 중지"}</Badge>
        </div>
      </div>
      <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-2">
        <div><dt className="text-xs text-muted-foreground">Hermes profile</dt><dd className="mt-1 break-all">{agent.hermesProfile}</dd></div>
        <div><dt className="text-xs text-muted-foreground">지난 기록이 쓰는 provider / 모델</dt><dd className="mt-1 break-all">{agent.provider} / {agent.model}</dd></div>
        <div><dt className="text-xs text-muted-foreground">모델을 마지막으로 읽은 시각</dt><dd className="mt-1">{agent.modelSyncedAt ? formatWhen(agent.modelSyncedAt) : "-"}</dd></div>
      </dl>
      <form onSubmit={(event) => void submitAddress(event)} className="mt-4">
        <div className="grid gap-1.5">
          <Label htmlFor={addressId}>Hermes API 주소</Label>
          <Input
            id={addressId}
            name="apiBaseUrl"
            // 지금 값이 바뀌면 다시 그려 새 값을 보인다. 빈 칸으로 두면 무엇이 바뀌는지 알 수 없다.
            key={agent.apiBaseUrl}
            defaultValue={agent.apiBaseUrl}
            aria-label={`${agent.name} Hermes API 주소`}
          />
        </div>
        <p className="mt-2 text-xs text-muted-foreground">저장하기 전에 이 주소가 응답하는지 확인한다.</p>
        {addressError ? (
          <p role="alert" className="mt-2 rounded-md bg-muted p-3 text-sm break-all">{addressError}</p>
        ) : null}
        <Button
          type="submit"
          size="sm"
          variant="outline"
          disabled={busy}
          loading={pendingAction === "address"}
          loadingText="확인하는 중"
          className="mt-2"
        >
          주소 저장
        </Button>
      </form>
      <div className="mt-4 flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          disabled={busy}
          loading={pendingAction === "private"}
          loadingText="바꾸는 중"
          onClick={() => onVisibilityChange(agent)}
        >
          {agent.visibility === PRIVATE_VISIBILITY ? "그룹 공개로 변경" : "나만으로 변경"}
        </Button>
        <Button
          size="sm"
          variant="ghost"
          disabled={busy}
          loading={pendingAction === "enabled"}
          loadingText={agent.enabled ? "중지하는 중" : "켜는 중"}
          onClick={() => onEnabledChange(agent)}
        >
          {agent.enabled ? "사용 중지" : "다시 사용"}
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={busy}
          loading={pendingAction === "sync"}
          loadingText="읽는 중"
          onClick={() => onSyncModel(agent)}
        >
          모델 다시 읽기
        </Button>
        <Button asChild size="sm" variant="outline">
          <Link href={`/agents/${agent.code}`}>성격 보기</Link>
        </Button>
      </div>
      <AgentModelList
        agentCode={agent.code}
        models={agent.models}
        busy={busy}
        saving={pendingAction === "models"}
        onSave={(models) => onSaveModels(agent, models)}
      />
    </article>
  );
}
