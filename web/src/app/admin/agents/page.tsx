import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { AgentAdminPanel } from "./agent-admin-panel";
import type { AdminAgent } from "@/lib/agent";
import { callControlPlane } from "@/lib/control-plane";

export default async function AgentAdminPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  const result = await callControlPlane<AdminAgent[]>("/api/v1/admin/agents");
  if (!result.ok) {
    if (result.status === 403) redirect("/");
    return <p className="text-sm">{result.message}</p>;
  }

  return <AgentAdminPanel initialAgents={result.data} ownerEmail={session.user.email} />;
}
