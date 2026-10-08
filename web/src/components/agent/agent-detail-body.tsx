"use client";

import { useState } from "react";
import { describeError } from "@/components/error-message";
import { Notice } from "@/components/ui/notice";
import { AgentAccessSection } from "./agent-access-section";
import { AgentAdminSection } from "./agent-admin-section";
import { AgentConnectionsSection } from "./agent-connections-section";
import { AgentMemorySection } from "./agent-memory-section";
import { AgentModelSection } from "./agent-model-section";
import { AgentProactiveCheckSection } from "./agent-proactive-check-section";
import { AgentProactiveScheduleSection } from "./agent-proactive-schedule-section";
import { AgentSkillsSection } from "./agent-skills-section";
import { AgentToolsSection } from "./agent-tools-section";
import { AgentValueEvaluationSection } from "./agent-value-evaluation-section";
import { PersonaEditor } from "./persona-editor";
import {
  GROUP_VISIBILITY,
  type AdminAgent,
  type AgentToolsView,
  type PersonaView,
} from "@/lib/agent";
import { fetchPersona } from "@/lib/agent-api";
import type { AgentConnectionsList } from "@/lib/agent-connection";
import type { ProactiveCheckStatus } from "@/lib/proactive-check";
import type { SkillListView } from "@/lib/skill";
import type { EvaluationOverview } from "@/lib/value-evaluation";

/** 서버가 읽어 온 값이거나, 읽지 못했을 때 화면에 보일 안내다. */
export type Loaded<T> = { ok: true; data: T } | { ok: false; message: string };

type Props = {
  code: string;
  name: string;
  /** 요청자가 읽을 수 없는 성격이면 null 이다. 관리자가 다른 사람의 비공개 에이전트를 열 때다. */
  initialPersona: PersonaView | null;
  /** 도구 절을 그리지 않으면 null 이다. */
  tools: Loaded<{ initialTools: AgentToolsView; admin: boolean }> | null;
  /** 스킬 절을 그리지 않으면 null 이다. 관리자가 다른 사람의 비공개 에이전트를 열 때다. */
  skills: Loaded<SkillListView> | null;
  /** 먼저 살펴보기 절을 그리지 않으면 null 이다. 예전 방식의 연결 에이전트와, 관리자가 읽지 못하는 다른 사람의 비공개 에이전트다. */
  proactiveCheck: Loaded<ProactiveCheckStatus> | null;
  /** 가치 평가 절을 그리지 않으면 null 이다. 관리자 영역에서 먼저 살펴보기 절을 그릴 때만 읽는다. */
  valueEvaluation?: Loaded<EvaluationOverview> | null;
  /** 「이 에이전트가 쓰는 연결」 절을 그리지 않으면 null 이다. 주인에게만 그린다. */
  connections?: Loaded<AgentConnectionsList> | null;
  initialVisibility: AdminAgent["visibility"] | undefined;
  adminAgent?: AdminAgent;
  /** 요청자가 이 에이전트의 공개 범위를 바꾸고 지울 수 있으면 참이다. 「공개와 삭제」 절을 그릴지 정한다. */
  canManageAccess: boolean;
  adminError: string | null;
  /** 예전 방식의 연결 에이전트면 일반 편집 절을 숨기고 지우기만 남긴다. */
  connectorManaged?: boolean;
  /** 에이전트를 지운 뒤 돌아갈 목록 주소다. */
  listHref: string;
};

type ErrorPayload = { code: string; message: string };

async function readPersona(code: string): Promise<Loaded<PersonaView>> {
  try {
    const response = await fetchPersona(code);
    if (response.ok)
      return { ok: true, data: (await response.json()) as PersonaView };
    const payload = (await response.json()) as ErrorPayload;
    return { ok: false, message: describeError(payload.code, payload.message) };
  } catch {
    return {
      ok: false,
      message: describeError("HERMES_UNAVAILABLE", "불러오지 못했어요."),
    };
  }
}

/**
 * 에이전트 상세 화면에서 공개 범위에 따라 달라지는 절을 한곳에서 그린다.
 *
 * <p>「공개와 삭제」 절이 공개 범위를 바꾸면 그 응답으로 이웃 절을 곧바로 바꾼다. 다른 사람의 비공개 에이전트를 그룹에
 * 공개하면 성격을 여기서 다시 읽는다. 전에는 `router.refresh()` 로 서버가 다시 그리게 했는데,
 * 빌드한 서버에서 refresh 에 대한 서버 응답이 맞게 나와도 화면에 반영되지 않은 채 옛 안내가 남는 일이
 * 있었다(Next 16.0.10). refresh 에 기대지 않고 이 화면이 가진 상태로 바꾼다.
 */
export function AgentDetailBody({
  code,
  name,
  initialPersona,
  tools,
  skills,
  proactiveCheck,
  valueEvaluation = null,
  connections = null,
  initialVisibility,
  adminAgent,
  canManageAccess,
  adminError,
  connectorManaged = false,
  listHref,
}: Props) {
  const [visibility, setVisibility] = useState(initialVisibility);
  // 도구 절과 연결 절이 서로의 위험을 알린다. 도구 절이 켜진 도구를, 연결 절이 붙은 연결이 있는지를 알려 준다.
  const [toolState, setToolState] = useState<AgentToolsView | null>(
    tools?.ok ? tools.data.initialTools : null,
  );
  const [hasConnections, setHasConnections] = useState(
    connections?.ok === true &&
      connections.data.connections.some((item) => item.bound),
  );
  const [persona, setPersona] = useState<Loaded<PersonaView> | null>(
    initialPersona ? { ok: true, data: initialPersona } : null,
  );

  async function changeVisibility(next: AdminAgent["visibility"]) {
    setVisibility(next);
    // 그룹에 공개하면 관리자도 성격을 읽을 수 있다. 비공개로 되돌리면 요청자가 주인이 되므로 그대로 읽힌다.
    if (persona !== null || next !== GROUP_VISIBILITY) return;
    setPersona(await readPersona(code));
  }

  return (
    <>
      {persona === null ? (
        <div className="mx-auto w-full max-w-2xl">
          <h1 className="mb-4 text-xl font-semibold">{name}</h1>
          <Notice variant="info">
            이 에이전트의 성격은 주인만 볼 수 있어요.
          </Notice>
        </div>
      ) : (
        <>
          {adminError ? (
            <div className="mx-auto mb-8 w-full max-w-2xl">
              <Notice variant="error" role="alert">
                관리 정보를 불러오지 못했어요. {adminError}
              </Notice>
            </div>
          ) : null}
          {connectorManaged ? (
            <div className="mx-auto w-full max-w-2xl">
              <h1 className="mb-4 text-xl font-semibold">{name}</h1>
              <Notice variant="info">
                예전 방식의 연결 에이전트예요. 쓰던 에이전트에 이 연결을 붙인 뒤
                이 에이전트를 지워 주세요.
              </Notice>
            </div>
          ) : persona.ok ? (
            <PersonaEditor
              code={code}
              name={name}
              initialPersona={persona.data}
            />
          ) : (
            <div className="mx-auto w-full max-w-2xl">
              <h1 className="mb-4 text-xl font-semibold">{name}</h1>
              <Notice variant="error" role="alert">
                {persona.message}
              </Notice>
            </div>
          )}
        </>
      )}
      {connectorManaged || tools === null ? null : tools.ok ? (
        <AgentToolsSection
          code={code}
          initialTools={tools.data.initialTools}
          admin={tools.data.admin}
          visibility={visibility}
          hasConnections={hasConnections}
          onToolsChange={setToolState}
        />
      ) : (
        <section
          aria-label="도구"
          className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
        >
          <h2 className="font-semibold">도구</h2>
          <Notice variant="error" role="alert" className="mt-3">
            {tools.message}
          </Notice>
        </section>
      )}
      {connectorManaged || connections === null ? null : connections.ok ? (
        <AgentConnectionsSection
          code={code}
          initialConnections={connections.data.connections}
          initialBlockedReason={connections.data.blockedReason}
          visibility={visibility}
          toolState={toolState}
          onBoundChange={setHasConnections}
        />
      ) : (
        <section
          aria-label="이 에이전트가 쓰는 연결"
          className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
        >
          <h2 className="font-semibold">이 에이전트가 쓰는 연결</h2>
          <Notice variant="error" role="alert" className="mt-3">
            {connections.message}
          </Notice>
        </section>
      )}
      {connectorManaged || skills === null ? null : skills.ok ? (
        <AgentSkillsSection code={code} initialSkills={skills.data} />
      ) : (
        <section
          aria-label="스킬"
          className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
        >
          <h2 className="font-semibold">스킬</h2>
          <Notice variant="error" role="alert" className="mt-3">
            {skills.message}
          </Notice>
        </section>
      )}
      {connectorManaged ||
      proactiveCheck === null ? null : proactiveCheck.ok ? (
        <AgentProactiveCheckSection
          code={code}
          initialStatus={proactiveCheck.data}
        />
      ) : (
        <section
          aria-label="먼저 살펴보기"
          className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
        >
          <h2 className="font-semibold">먼저 살펴보기</h2>
          <Notice variant="error" role="alert" className="mt-3">
            {proactiveCheck.message}
          </Notice>
          <AgentProactiveScheduleSection code={code} />
        </section>
      )}
      {connectorManaged ||
      valueEvaluation === null ? null : valueEvaluation.ok ? (
        <AgentValueEvaluationSection initialOverview={valueEvaluation.data} />
      ) : (
        <section
          aria-label="가치 평가"
          className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
        >
          <h2 className="font-semibold">가치 평가</h2>
          <Notice variant="error" role="alert" className="mt-3">
            {valueEvaluation.message}
          </Notice>
        </section>
      )}
      {adminAgent ? <AgentModelSection code={code} /> : null}
      {adminAgent ? <AgentMemorySection code={code} /> : null}
      {adminAgent && visibility ? (
        <AgentAdminSection initialAgent={adminAgent} visibility={visibility} />
      ) : null}
      {canManageAccess && visibility ? (
        <AgentAccessSection
          code={code}
          name={name}
          visibility={visibility}
          listHref={listHref}
          onVisibilityChange={(next) => void changeVisibility(next)}
          connectorManaged={connectorManaged}
        />
      ) : null}
    </>
  );
}
