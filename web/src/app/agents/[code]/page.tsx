import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { describeError } from "@/components/error-message";
import { PersonaEditor } from "@/components/agent/persona-editor";
import { StarterEditor } from "@/components/agent/starter-editor";
import { AgentAdminSection } from "@/components/agent/agent-admin-section";
import { callControlPlane } from "@/lib/control-plane";
import type { AdminAgent, AgentView, PersonaView, StartersView } from "@/lib/agent";
import { readMe } from "@/lib/me";

export default async function AgentPersonaPage({
  params,
}: {
  params: Promise<{ code: string }>;
}) {
  const { code } = await params;
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  const me = await readMe();
  const [agentsResult, personaResult, startersResult, adminAgentsResult] = await Promise.all([
    callControlPlane<AgentView[]>("/api/v1/agents"),
    callControlPlane<PersonaView>(`/api/v1/agents/${code}/persona`),
    callControlPlane<StartersView>(`/api/v1/agents/${code}/starters`),
    me?.role === "ADMIN"
      ? callControlPlane<AdminAgent[]>("/api/v1/admin/agents")
      : Promise.resolve(null),
  ]);
  const adminAgent = adminAgentsResult?.ok
    ? adminAgentsResult.data.find((agent) => agent.code === code)
    : undefined;
  const name = adminAgent?.name ?? (agentsResult.ok
    ? (agentsResult.data.find((agent) => agent.code === code)?.name ?? code)
    : code);

  if (!personaResult.ok) {
    if (personaResult.code === "AGENT_NOT_FOUND" && adminAgent && session.user.email) {
      return (
        <>
          <div className="mx-auto w-full max-w-2xl">
            <h1 className="mb-4 text-xl font-semibold">{name}</h1>
            <p className="rounded-md border border-border bg-muted p-3 text-sm">
              이 에이전트의 성격은 주인만 볼 수 있어요.
            </p>
          </div>
          <AgentAdminSection initialAgent={adminAgent} ownerEmail={session.user.email} />
        </>
      );
    }
    return (
      <div className="mx-auto w-full max-w-2xl">
        <h1 className="mb-4 text-xl font-semibold">{name}</h1>
        <p role="alert" className="rounded-md border border-border bg-muted p-3 text-sm">
          {describeError(personaResult.code, personaResult.message)}
        </p>
      </div>
    );
  }

  return (
    <>
      <PersonaEditor code={code} name={name} initialPersona={personaResult.data} />
      {startersResult.ok ? (
        <StarterEditor code={code} name={name} initialStarters={startersResult.data} />
      ) : (
        <div className="mx-auto mt-8 w-full max-w-2xl">
          <p role="alert" className="rounded-md border border-border bg-muted p-3 text-sm">
            {describeError(startersResult.code, startersResult.message)}
          </p>
        </div>
      )}
      {adminAgent && session.user.email ? <AgentAdminSection initialAgent={adminAgent} ownerEmail={session.user.email} /> : null}
    </>
  );
}
