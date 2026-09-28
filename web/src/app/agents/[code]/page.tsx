import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { describeError } from "@/components/error-message";
import { PersonaEditor } from "@/components/agent/persona-editor";
import { StarterEditor } from "@/components/agent/starter-editor";
import { AgentAdminSection } from "@/components/agent/agent-admin-section";
import { AgentToolsSection } from "@/components/agent/agent-tools-section";
import { callControlPlane } from "@/lib/control-plane";
import type { AdminAgent, AgentToolsView, AgentView, PersonaView, StartersView } from "@/lib/agent";
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
  const agentsResult = await callControlPlane<AgentView[]>("/api/v1/agents");
  // 관리자는 자기 에이전트에도 일반 경로를 쓴다. 목록에 없는 다른 사람의 비공개 에이전트만 관리 경로로 읽는다.
  const useAdminTools = me?.role === "ADMIN" && !(agentsResult.ok && agentsResult.data.some((agent) => agent.code === code));
  const [personaResult, startersResult, adminAgentsResult, toolsResult] = await Promise.all([
    callControlPlane<PersonaView>(`/api/v1/agents/${code}/persona`),
    callControlPlane<StartersView>(`/api/v1/agents/${code}/starters`),
    me?.role === "ADMIN"
      ? callControlPlane<AdminAgent[]>("/api/v1/admin/agents")
      : Promise.resolve(null),
    useAdminTools
      ? callControlPlane<AgentToolsView>(`/api/v1/admin/agents/${code}/tools`)
      : callControlPlane<AgentToolsView>(`/api/v1/agents/${code}/tools`),
  ]);
  const adminAgent = adminAgentsResult?.ok
    ? adminAgentsResult.data.find((agent) => agent.code === code)
    : undefined;
  const adminError = me?.role === "ADMIN" && adminAgentsResult && !adminAgentsResult.ok
    ? describeError(adminAgentsResult.code, adminAgentsResult.message)
    : null;
  const name = adminAgent?.name ?? (agentsResult.ok
    ? (agentsResult.data.find((agent) => agent.code === code)?.name ?? code)
    : code);

  const toolsSection = toolsResult.ok ? (
    <AgentToolsSection
      code={code}
      initialTools={toolsResult.data}
      admin={useAdminTools}
      visibility={adminAgent?.visibility ?? (agentsResult.ok ? agentsResult.data.find((agent) => agent.code === code)?.visibility : undefined)}
    />
  ) : toolsResult.code === "FORBIDDEN" ? null : (
    <section aria-label="도구" className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4">
      <h2 className="font-semibold">도구</h2>
      <p role="alert" className="mt-3 rounded-md bg-muted p-3 text-sm">
        {describeError(toolsResult.code, toolsResult.message)}
      </p>
    </section>
  );

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
          {toolsSection}
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
        {adminError ? (
          <p role="alert" className="mt-4 rounded-md border border-border bg-muted p-3 text-sm">
            관리 정보를 불러오지 못했습니다. {adminError}
          </p>
        ) : null}
      </div>
    );
  }

  return (
    <>
      {adminError ? (
        <div className="mx-auto mb-8 w-full max-w-2xl">
          <p role="alert" className="rounded-md border border-border bg-muted p-3 text-sm">
            관리 정보를 불러오지 못했습니다. {adminError}
          </p>
        </div>
      ) : null}
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
      {toolsSection}
      {adminAgent && session.user.email ? <AgentAdminSection initialAgent={adminAgent} ownerEmail={session.user.email} /> : null}
    </>
  );
}
