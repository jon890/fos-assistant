import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ModelAdminPanel } from "@/components/admin/model-admin-panel";
import { callControlPlane } from "@/lib/control-plane";
import type { AdminAgent } from "@/lib/agent";

export default async function ModelAdminPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  const result = await callControlPlane<AdminAgent[]>("/api/v1/admin/agents");
  if (!result.ok) {
    if (result.status === 403) redirect("/");
    return <p className="text-sm">{result.message}</p>;
  }

  const agents = result.data
    .filter((agent) => agent.enabled)
    .map(({ code, name }) => ({ code, name }));
  return <ModelAdminPanel agents={agents} />;
}
