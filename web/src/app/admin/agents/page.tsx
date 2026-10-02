import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { AgentAdminPanel } from "@/components/agent/agent-admin-panel";
import { callControlPlane } from "@/lib/control-plane";
import type { AdminAgent } from "@/lib/agent";
import { readMe } from "@/lib/me";

export default async function AgentAdminPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  const [result, me] = await Promise.all([
    callControlPlane<AdminAgent[]>("/api/v1/admin/agents"),
    readMe(),
  ]);
  if (!result.ok) {
    if (result.status === 403) redirect("/");
    return <p className="text-sm">{result.message}</p>;
  }
  // 레이아웃이 ADMIN 만 들이므로 역할을 읽지 못하는 일은 드물다. 그래도 id 없이 목록을 그리지 않는다.
  if (!me) return <p className="text-sm">계정 정보를 읽지 못했어요.</p>;

  return (
    <AgentAdminPanel
      initialAgents={result.data}
      ownerEmail={session.user.email}
      currentUserId={me.id}
    />
  );
}
