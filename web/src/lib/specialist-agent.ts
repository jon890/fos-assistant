import type { AgentConnectionView } from "@/lib/agent-connection";
import type { AgentColor } from "@/lib/agent-badge";
import { connectionCall, type BoundAgent } from "./connection.ts";
import { withParticle } from "./korean-particle.ts";

/**
 * 전문 에이전트 만들기(마법사)의 화면 규칙이다(docs/features/agent-team/design.md 의 「PR3 마법사」).
 * 제안을 띄울지, 미리 채울 값, 떼기 칸의 글과 잠금을 여기서 정한다.
 */

/**
 * 미리 고를 아이콘이다. `AgentIntroduction.ICONS` 안의 값이다. 운영 코드는 특정 플러그인을 모르므로 플러그인과 상관없이
 * 같은 값을 고르고, 사용자가 만들기 화면에서 바꾼다.
 */
const DEFAULT_ICON = "🤖";

/** 미리 채운 값이다. 사용자가 고친 뒤 보낸다. */
export type SpecialistDraft = {
  name: string;
  description: string;
  icon: string;
};

export function specialistDraft(title: string): SpecialistDraft {
  return {
    name: `${title} 담당`,
    description: `${title} 일을 맡아요`,
    icon: DEFAULT_ICON,
  };
}

/**
 * 연결을 마친 화면에서 제안을 띄울지 정한다.
 *
 * <p>방금 연결을 마쳤고 그 연결이 아직 어느 에이전트에도 붙지 않았을 때만 띄운다. 붙은 곳이 없으니 그 서비스가 붙은 전문
 * 에이전트도 없다. 에이전트 화면에서 그 에이전트에 붙이러 왔으면 띄우지 않는다.
 */
export function shouldOfferSpecialist(input: {
  justConnected: boolean;
  bindings: readonly BoundAgent[];
  preferredAgent: string | null;
}): boolean {
  return (
    input.justConnected &&
    input.bindings.length === 0 &&
    input.preferredAgent === null
  );
}

/** 떼기 칸이다. 붙은 다른 에이전트가 없으면 칸이 없다(null). */
export type DetachOption = {
  label: string;
  /** `single_binding` 이라 켠 채 잠근다. */
  locked: boolean;
};

export function detachOption(
  bindings: readonly BoundAgent[],
  singleBinding: boolean,
): DetachOption | null {
  if (bindings.length === 0) return null;
  const names = bindings
    .map((binding) => `「${binding.agentName}」`)
    .join(", ");
  return {
    label: `${names}에서는 떼고 맡기기만`,
    locked: singleBinding,
  };
}

/** 잠긴 떼기 칸 아래에 적는 까닭이다. */
export const DETACH_LOCKED_REASON =
  "이 서비스는 에이전트 하나에만 붙어요. 새 에이전트에 붙이려면 지금 붙은 곳에서 떼야 해요.";

/** 만든 뒤 보이는 한 줄이다. */
export function createdMessage(agentName: string, title: string): string {
  return `「${agentName}」${objectParticle(agentName)} 만들고 「${title}」${objectParticle(title)} 붙였어요.`;
}

/** 낫표로 감싼 이름 뒤에 붙일 목적격 조사다. 조사는 낫표가 아니라 이름의 끝 글자로 정한다. */
function objectParticle(name: string): string {
  return withParticle(name, "을", "를").slice(name.length);
}

export type SpecialistAgentResult = {
  agentCode: string;
  agentName: string;
  connection: AgentConnectionView;
  detachedFrom: { agentCode: string; agentName: string }[];
};

export const createSpecialistAgent = (
  connectorId: string,
  body: {
    name: string;
    description: string;
    icon: string | null;
    color: AgentColor | null;
    detach: boolean;
  },
) =>
  connectionCall<SpecialistAgentResult>(
    `/api/connections/${connectorId}/specialist`,
    { method: "POST", body },
  );
