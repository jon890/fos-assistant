import Link from "next/link";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { EmptyState } from "@/components/ui/empty-state";
import { callControlPlane } from "@/lib/control-plane";
import type { AgentView } from "@/lib/agent";

export default async function AgentsPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  const result = await callControlPlane<AgentView[]>("/api/v1/agents");
  if (!result.ok) return <p className="text-sm">{result.message}</p>;

  return (
    <div className="mx-auto w-full max-w-2xl">
      <h1 className="mb-6 text-xl font-semibold">에이전트</h1>
      {result.data.length === 0 ? (
        <EmptyState
          title="쓸 수 있는 에이전트가 없습니다"
          description="관리자가 에이전트를 연결하면 여기에 나타납니다"
        />
      ) : (
        <ul aria-label="쓸 수 있는 에이전트" className="grid gap-3">
          {result.data.map((agent) => (
            <li key={agent.code}>
              <Link
                href={`/agents/${agent.code}`}
                className="flex items-center justify-between rounded-md border border-border p-4 hover:bg-surface-raised"
              >
                <span className="font-medium">{agent.name}</span>
                <span className="text-sm text-muted">{agent.model}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
