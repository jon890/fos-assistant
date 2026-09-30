"use client";

import { useState } from "react";
import { describeError } from "@/components/error-message";
import { AgentAccessSection } from "./agent-access-section";
import { AgentAdminSection } from "./agent-admin-section";
import { AgentSkillsSection } from "./agent-skills-section";
import { AgentToolsSection } from "./agent-tools-section";
import { PersonaEditor } from "./persona-editor";
import {
  GROUP_VISIBILITY,
  type AdminAgent,
  type AgentToolsView,
  type PersonaView,
} from "@/lib/agent";
import type { SkillListView } from "@/lib/skill";

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
  initialVisibility: AdminAgent["visibility"] | undefined;
  adminAgent?: AdminAgent;
  /** 요청자가 이 에이전트의 공개 범위를 바꾸고 지울 수 있으면 참이다. 「공개와 삭제」 절을 그릴지 정한다. */
  canManageAccess: boolean;
  adminError: string | null;
};

type ErrorPayload = { code: string; message: string };

async function read<T>(path: string): Promise<Loaded<T>> {
  try {
    const response = await fetch(path, { cache: "no-store" });
    if (response.ok) return { ok: true, data: (await response.json()) as T };
    const payload = (await response.json()) as ErrorPayload;
    return { ok: false, message: describeError(payload.code, payload.message) };
  } catch {
    return { ok: false, message: describeError("HERMES_UNAVAILABLE", "불러오지 못했어요.") };
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
  initialVisibility,
  adminAgent,
  canManageAccess,
  adminError,
}: Props) {
  const [visibility, setVisibility] = useState(initialVisibility);
  const [persona, setPersona] = useState<Loaded<PersonaView> | null>(
    initialPersona ? { ok: true, data: initialPersona } : null,
  );

  async function changeVisibility(next: AdminAgent["visibility"]) {
    setVisibility(next);
    // 그룹에 공개하면 관리자도 성격을 읽을 수 있다. 비공개로 되돌리면 요청자가 주인이 되므로 그대로 읽힌다.
    if (persona !== null || next !== GROUP_VISIBILITY) return;
    setPersona(await read<PersonaView>(`/api/agents/${code}/persona`));
  }

  return (
    <>
      {persona === null ? (
        <div className="mx-auto w-full max-w-2xl">
          <h1 className="mb-4 text-xl font-semibold">{name}</h1>
          <p className="rounded-md border border-border bg-muted p-3 text-sm">
            이 에이전트의 성격은 주인만 볼 수 있어요.
          </p>
        </div>
      ) : (
        <>
          {adminError ? (
            <div className="mx-auto mb-8 w-full max-w-2xl">
              <p role="alert" className="rounded-md border border-border bg-muted p-3 text-sm">
                관리 정보를 불러오지 못했어요. {adminError}
              </p>
            </div>
          ) : null}
          {persona.ok ? (
            <PersonaEditor code={code} name={name} initialPersona={persona.data} />
          ) : (
            <div className="mx-auto w-full max-w-2xl">
              <h1 className="mb-4 text-xl font-semibold">{name}</h1>
              <p role="alert" className="rounded-md border border-border bg-muted p-3 text-sm">{persona.message}</p>
            </div>
          )}
        </>
      )}
      {tools === null ? null : tools.ok ? (
        <AgentToolsSection code={code} initialTools={tools.data.initialTools} admin={tools.data.admin}
          visibility={visibility} />
      ) : (
        <section aria-label="도구" className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4">
          <h2 className="font-semibold">도구</h2>
          <p role="alert" className="mt-3 rounded-md bg-muted p-3 text-sm">{tools.message}</p>
        </section>
      )}
      {skills === null ? null : skills.ok ? (
        <AgentSkillsSection code={code} initialSkills={skills.data} />
      ) : (
        <section aria-label="스킬" className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4">
          <h2 className="font-semibold">스킬</h2>
          <p role="alert" className="mt-3 rounded-md bg-muted p-3 text-sm">{skills.message}</p>
        </section>
      )}
      {adminAgent && visibility ? <AgentAdminSection initialAgent={adminAgent} visibility={visibility} /> : null}
      {canManageAccess && visibility ? (
        <AgentAccessSection code={code} name={name} visibility={visibility}
          onVisibilityChange={(next) => void changeVisibility(next)} />
      ) : null}
    </>
  );
}
