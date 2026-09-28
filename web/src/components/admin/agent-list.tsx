import { EmptyState } from "@/components/ui/empty-state";
import type { AdminAgent } from "@/lib/agent";
import { AgentCard, type AgentAction } from "./agent-card";

type Props = {
  agents: AdminAgent[];
  currentUserId: number;
  busy: boolean;
  /** 도는 요청과 그 대상 에이전트다. 그 카드의 그 단추에만 회전 표시를 둔다. */
  pending: { code: string; action: AgentAction } | null;
  onVisibilityChange(agent: AdminAgent): void;
  onEnabledChange(agent: AdminAgent): void;
  onSyncModel(agent: AdminAgent): void;
  onSaveModels(agent: AdminAgent, models: { provider: string; model: string }[]): void;
  onApiBaseUrlChange(agent: AdminAgent, apiBaseUrl: string): Promise<string | null>;
};

export function AgentList(props: Props) {
  if (props.agents.length === 0) {
    return <EmptyState title="등록된 에이전트가 없다" description="위 양식에서 첫 에이전트를 등록한다." />;
  }
  return (
    <section aria-label="등록된 에이전트" className="grid gap-4">
      {props.agents.map((agent) => (
        <AgentCard
          key={agent.code}
          agent={agent}
          currentUserId={props.currentUserId}
          busy={props.busy}
          pendingAction={props.pending?.code === agent.code ? props.pending.action : null}
          onVisibilityChange={props.onVisibilityChange}
          onEnabledChange={props.onEnabledChange}
          onSyncModel={props.onSyncModel}
          onSaveModels={props.onSaveModels}
          onApiBaseUrlChange={props.onApiBaseUrlChange}
        />
      ))}
    </section>
  );
}
