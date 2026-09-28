import Link from "next/link";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { callControlPlane } from "@/lib/control-plane";
import { AgentAdminPanel } from "@/components/agent/agent-admin-panel";
import type { AdminAgent, AgentView } from "@/lib/agent";
import { readMe } from "@/lib/me";

export default async function AgentsPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  const me = await readMe();
  if (me?.role === "ADMIN") {
    const result = await callControlPlane<AdminAgent[]>("/api/v1/admin/agents");
    if (!result.ok) return <p className="text-sm">{result.message}</p>;
    return <AgentAdminPanel initialAgents={result.data} ownerEmail={session.user.email} currentUserId={me.id} />;
  }

  const result = await callControlPlane<AgentView[]>("/api/v1/agents");
  if (!result.ok) return <p className="text-sm">{result.message}</p>;

  return (
    <div className="mx-auto w-full max-w-2xl">
      <h1 className="mb-6 text-xl font-semibold">에이전트</h1>
      {result.data.length === 0 ? (
        <EmptyState
          title="사용할 수 있는 에이전트가 없어요"
          description="관리자가 에이전트를 연결하면 여기에 표시돼요"
        />
      ) : (
        <ul aria-label="쓸 수 있는 에이전트" className="grid gap-3">
          {result.data.map((agent) => (
            <li key={agent.code}>
              <Link href={`/agents/${agent.code}`} className="block rounded-xl">
                <Card className="flex-row items-center justify-between px-4 hover:bg-accent">
                  <span className="text-base font-medium">{agent.name}</span>
                  <span className="text-muted-foreground">{agent.model}</span>
                </Card>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
