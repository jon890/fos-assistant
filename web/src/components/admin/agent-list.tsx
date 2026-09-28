import { EmptyState } from "@/components/ui/empty-state";
import type { AdminAgent } from "@/lib/agent";
import { AgentCard } from "./agent-card";

type Props = {
  agents: AdminAgent[];
  currentUserId: number;
};

export function AgentList(props: Props) {
  if (props.agents.length === 0) {
    return <EmptyState title="등록된 에이전트가 없어요" description="위 양식에서 첫 에이전트를 등록해 주세요." />;
  }
  return (
    <section aria-label="등록된 에이전트" className="grid gap-4">
      {props.agents.map((agent) => (
        <AgentCard
          key={agent.code}
          agent={agent}
          currentUserId={props.currentUserId}
        />
      ))}
    </section>
  );
}
