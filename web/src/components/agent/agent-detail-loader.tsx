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
import type { AgentConnectionsList } from "@/lib/agent-connection";
import type { ProactiveCheckStatus } from "@/lib/proactive-check";
import type { SkillListView } from "@/lib/skill";
import type { EvaluationOverview } from "@/lib/value-evaluation";

/**
 * 에이전트 상세 화면을 읽어 그린다. 일반 화면과 관리자 영역이 함께 쓴다.
 *
 * <p>`admin` 이 참이면 관리자 목록과 관리 경로를 읽어 「모델」, 「기억 영역」, 「관리」 절을 더한다.
 * 거짓이면 역할과 상관없이 내가 쓸 수 있는 에이전트 목록만 읽는다.
 */
export async function loadAgentDetail(
  code: string,
  options: { admin: boolean },
) {
  const { admin } = options;
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  const agentsResult = await callControlPlane<AgentView[]>("/api/v1/agents");
  // 관리자 영역에서는 자기 에이전트도 관리 경로로 읽어 숨긴 도구를 확인하고 끌 수 있게 한다.
  const useAdminTools = admin;
  // 예전 방식의 연결 에이전트는 살펴보기를 하지 않고 다른 연결을 받지 않으므로 둘 다 읽지 않는다.
  const listedAgent = agentsResult.ok
    ? agentsResult.data.find((agent) => agent.code === code)
    : undefined;
  // 연결을 붙이고 떼는 것은 주인만 한다. 관리자도 남의 에이전트의 연결 절은 그리지 않는다.
  const readsConnections =
    listedAgent?.ownedByMe === true && listedAgent.connectorManaged !== true;
  const [
    personaResult,
    adminAgentsResult,
    toolsResult,
    skillsResult,
    proactiveCheckResult,
    connectionsResult,
  ] = await Promise.all([
    callControlPlane<PersonaView>(`/api/v1/agents/${code}/persona`),
    admin
      ? callControlPlane<AdminAgent[]>("/api/v1/admin/agents")
      : Promise.resolve(null),
    useAdminTools
      ? callControlPlane<AgentToolsView>(`/api/v1/admin/agents/${code}/tools`)
      : callControlPlane<AgentToolsView>(`/api/v1/agents/${code}/tools`),
    callControlPlane<SkillListView>(`/api/v1/agents/${code}/skills`),
    listedAgent?.connectorManaged === true
      ? Promise.resolve(null)
      : callControlPlane<ProactiveCheckStatus>(
          `/api/v1/agents/${code}/proactive-check`,
        ),
    readsConnections
      ? callControlPlane<AgentConnectionsList>(
          `/api/v1/agents/${code}/connections`,
        )
      : Promise.resolve(null),
  ]);
  // 살펴보기 절을 그리는 에이전트에서만 읽는다. 상태 조회를 본 뒤여야 하므로 위 묶음에 넣지 않는다.
  const valueEvaluationResult =
    admin && proactiveCheckResult?.ok
      ? await callControlPlane<EvaluationOverview>(
          `/api/v1/admin/agents/${code}/value-evaluation`,
        )
      : null;
  const valueEvaluation =
    valueEvaluationResult === null
      ? null
      : valueEvaluationResult.ok
        ? { ok: true as const, data: valueEvaluationResult.data }
        : {
            ok: false as const,
            message: describeError(
              valueEvaluationResult.code,
              valueEvaluationResult.message,
            ),
          };
  const adminAgent = adminAgentsResult?.ok
    ? adminAgentsResult.data.find((agent) => agent.code === code)
    : undefined;
  const adminError =
    admin && adminAgentsResult && !adminAgentsResult.ok
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
  // 관리자가 대화를 시작할 수 없는 다른 사람의 비공개 에이전트는 404 라 읽지 못한다. 그때 절을 그리지 않는다.
  const proactiveCheck =
    proactiveCheckResult === null
      ? null
      : proactiveCheckResult.ok
        ? { ok: true as const, data: proactiveCheckResult.data }
        : admin
          ? null
          : {
              ok: false as const,
              message: describeError(
                proactiveCheckResult.code,
                proactiveCheckResult.message,
              ),
            };
  const connections =
    connectionsResult === null
      ? null
      : connectionsResult.ok
        ? { ok: true as const, data: connectionsResult.data }
        : {
            ok: false as const,
            message: describeError(
              connectionsResult.code,
              connectionsResult.message,
            ),
          };
  const visibility =
    adminAgent?.visibility ??
    (agentsResult.ok
      ? agentsResult.data.find((agent) => agent.code === code)?.visibility
      : undefined);
  // 목록의 editable 은 주인과 ADMIN 에게 참이다. 관리자 영역이 관리자 목록으로만 찾은 다른 사람의 비공개 에이전트도 관리한다.
  const listed = agentsResult.ok
    ? agentsResult.data.find((agent) => agent.code === code)
    : undefined;
  const connectorManaged =
    listed?.connectorManaged === true || adminAgent?.connectorManaged === true;
  // 예전 방식의 연결 에이전트도 이 절을 그린다. 공개 범위는 숨기고 지우기만 남긴다.
  // 그 에이전트는 편집 대상이 아니라 목록의 editable 이 거짓이므로 주인인지로 정한다.
  const canManageAccess =
    listed?.editable === true ||
    adminAgent !== undefined ||
    (connectorManaged && listed?.ownedByMe === true);
  const listHref = admin ? "/admin/agents" : "/agents";

  if (!personaResult.ok) {
    if (personaResult.code === "AGENT_NOT_FOUND" && adminAgent) {
      return (
        <AgentDetailBody
          code={code}
          name={name}
          initialPersona={null}
          tools={tools}
          skills={skills}
          proactiveCheck={proactiveCheck}
          valueEvaluation={valueEvaluation}
          connections={connections}
          initialVisibility={visibility}
          adminAgent={connectorManaged ? undefined : adminAgent}
          canManageAccess={canManageAccess}
          adminError={null}
          connectorManaged={connectorManaged}
          listHref={listHref}
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
      proactiveCheck={proactiveCheck}
      valueEvaluation={valueEvaluation}
      connections={connections}
      initialVisibility={visibility}
      adminAgent={connectorManaged ? undefined : adminAgent}
      canManageAccess={canManageAccess}
      adminError={adminError}
      connectorManaged={connectorManaged}
      listHref={listHref}
    />
  );
}
