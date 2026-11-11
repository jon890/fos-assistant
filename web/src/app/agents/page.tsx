import Link from "next/link";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { callControlPlane } from "@/lib/control-plane";
import { CreateAgentDialog } from "@/components/agent/create-agent-dialog";
import type { AgentView } from "@/lib/agent";
import { capabilitiesLine } from "@/lib/agent-badge";
import { AgentBadge } from "@/components/agent/agent-badge";

export default async function AgentsPage({
  searchParams,
}: {
  /** `new=1` 이면 「새 에이전트」 창을 열어 둔다. */
  searchParams: Promise<{ new?: string | string[] }>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  const { new: openNew } = await searchParams;
  const result = await callControlPlane<AgentView[]>("/api/v1/agents");
  if (!result.ok) return <p className="text-sm">{result.message}</p>;

  return (
    <div className="mx-auto w-full max-w-2xl">
      <div className="mb-6 flex items-center justify-between gap-3">
        <h1 className="text-xl font-semibold">에이전트</h1>
        <CreateAgentDialog defaultOpen={openNew === "1"} />
      </div>
      {result.data.length === 0 ? (
        <EmptyState
          title="아직 에이전트가 없어요"
          description="새 에이전트를 만들어 보세요"
        />
      ) : (
        <ul aria-label="쓸 수 있는 에이전트" className="grid gap-3">
          {result.data.map((agent) => (
            <li key={agent.code}>
              <Link
                prefetch={false}
                href={`/agents/${agent.code}`}
                className="block rounded-lg"
              >
                <Card className="flex-row items-center gap-3 px-4 hover:bg-accent">
                  <AgentBadge agent={agent} />
                  <span className="min-w-0">
                    <span className="block truncate text-base font-medium">
                      {agent.name}
                    </span>
                    {agent.description ? (
                      <span className="block truncate text-sm text-muted-foreground">
                        {agent.description}
                      </span>
                    ) : null}
                    {capabilitiesLine(agent.capabilities) ? (
                      <span className="block truncate text-xs text-muted-foreground">
                        {capabilitiesLine(agent.capabilities)}
                      </span>
                    ) : null}
                  </span>
                </Card>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
