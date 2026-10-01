import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { describeAdminError, describeError } from "@/components/error-message";
import { AgentDetailBody } from "@/components/agent/agent-detail-body";
import { Notice } from "@/components/ui/notice";
import { callControlPlane } from "@/lib/control-plane";
import type {
  AdminAgent,
  AgentToolsView,
  AgentView,
  PersonaView,
} from "@/lib/agent";
import type { SkillListView } from "@/lib/skill";
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
  const useAdminTools =
    me?.role === "ADMIN" &&
    !(
      agentsResult.ok && agentsResult.data.some((agent) => agent.code === code)
    );
  const [personaResult, adminAgentsResult, toolsResult, skillsResult] =
    await Promise.all([
      callControlPlane<PersonaView>(`/api/v1/agents/${code}/persona`),
      me?.role === "ADMIN"
        ? callControlPlane<AdminAgent[]>("/api/v1/admin/agents")
        : Promise.resolve(null),
      useAdminTools
        ? callControlPlane<AgentToolsView>(`/api/v1/admin/agents/${code}/tools`)
        : callControlPlane<AgentToolsView>(`/api/v1/agents/${code}/tools`),
      callControlPlane<SkillListView>(`/api/v1/agents/${code}/skills`),
    ]);
  const adminAgent = adminAgentsResult?.ok
    ? adminAgentsResult.data.find((agent) => agent.code === code)
    : undefined;
  const adminError =
    me?.role === "ADMIN" && adminAgentsResult && !adminAgentsResult.ok
      ? describeAdminError(adminAgentsResult.code, adminAgentsResult.message)
      : null;
  const name =
    adminAgent?.name ??
    (agentsResult.ok
      ? (agentsResult.data.find((agent) => agent.code === code)?.name ?? code)
      : code);

  const tools = toolsResult.ok
    ? {
        ok: true as const,
        data: { initialTools: toolsResult.data, admin: useAdminTools },
      }
    : toolsResult.code === "FORBIDDEN"
      ? null
      : {
          ok: false as const,
          message: describeError(toolsResult.code, toolsResult.message),
        };
  // 볼 수 없는 에이전트(관리자가 다른 사람의 비공개 에이전트를 연 경우)는 스킬 절을 그리지 않는다.
  const skills = skillsResult.ok
    ? { ok: true as const, data: skillsResult.data }
    : skillsResult.code === "AGENT_NOT_FOUND"
      ? null
      : {
          ok: false as const,
          message: describeError(skillsResult.code, skillsResult.message),
        };
  const visibility =
    adminAgent?.visibility ??
    (agentsResult.ok
      ? agentsResult.data.find((agent) => agent.code === code)?.visibility
      : undefined);
  // 목록의 editable 은 주인과 ADMIN 에게 참이다. ADMIN 이 관리자 목록으로만 찾은 다른 사람의 비공개 에이전트도 관리한다.
  const listed = agentsResult.ok
    ? agentsResult.data.find((agent) => agent.code === code)
    : undefined;
  const connectorManaged =
    listed?.connectorManaged === true || adminAgent?.connectorManaged === true;
  const canManageAccess =
    !connectorManaged &&
    (listed?.editable === true || adminAgent !== undefined);

  if (!personaResult.ok) {
    if (personaResult.code === "AGENT_NOT_FOUND" && adminAgent) {
      return (
        <AgentDetailBody
          code={code}
          name={name}
          initialPersona={null}
          tools={tools}
          skills={skills}
          initialVisibility={visibility}
          adminAgent={connectorManaged ? undefined : adminAgent}
          canManageAccess={canManageAccess}
          adminError={null}
          connectorManaged={connectorManaged}
        />
      );
    }
    return (
      <div className="mx-auto w-full max-w-2xl">
        <h1 className="mb-4 text-xl font-semibold">{name}</h1>
        <Notice variant="error" role="alert">
          {describeError(personaResult.code, personaResult.message)}
        </Notice>
        {adminError ? (
          <Notice variant="error" role="alert" className="mt-4">
            관리 정보를 불러오지 못했어요. {adminError}
          </Notice>
        ) : null}
      </div>
    );
  }

  return (
    <AgentDetailBody
      code={code}
      name={name}
      initialPersona={personaResult.data}
      tools={tools}
      skills={skills}
      initialVisibility={visibility}
      adminAgent={connectorManaged ? undefined : adminAgent}
      canManageAccess={canManageAccess}
      adminError={adminError}
      connectorManaged={connectorManaged}
    />
  );
}
