/**
 * 대화 중 `@` 로 고른 에이전트에게 바로 맡기는 화면 값이다(docs/features/agent-team/design.md 의 「PR4 @ 멘션」).
 */
import type { AgentView } from "./agent.ts";
import type { AgentIdentity } from "@/components/agent/agent-badge";
import {
  labelIdentity,
  type AgentLabel,
  type DelegationLine,
} from "./delegation.ts";

/**
 * 맡김 질문 하나다. `MessageView.delegatedTo` 를 그대로 받는다. 맡긴 줄과 같은 모양이고 부모 실행이 없다.
 * 아직 보내는 중인 질문은 실행 번호를 몰라 null 이다.
 */
export type MentionDelegation = Omit<
  DelegationLine,
  "executionId" | "parentExecutionId"
> & {
  executionId: number | null;
  parentExecutionId: null;
};

/** `@` 목록에 넣을 에이전트다. 요청자가 쓸 수 있고 켜진 에이전트에서 대화의 에이전트를 뺀다(spec DECISION21) */
export function mentionTargets(
  agents: AgentView[],
  conversationAgentCode: string,
): AgentView[] {
  return agents.filter((agent) => agent.code !== conversationAgentCode);
}

/** 보내는 중인 맡김 질문에 붙일 값이다. 서버가 실행 번호를 주기 전까지 진행 중으로 그린다 */
export function pendingMention(agent: AgentView): MentionDelegation {
  return {
    executionId: null,
    parentExecutionId: null,
    status: "RUNNING",
    agent: labelOf(agent),
  };
}

/** 에이전트 목록의 한 줄을 이름표 값으로 바꾼다 */
export function labelOf(agent: AgentView): AgentLabel {
  return {
    code: agent.code,
    name: agent.name,
    icon: agent.icon ?? null,
    color: agent.color ?? null,
  };
}

/**
 * 아직 저장되지 않은 답과 기다림 줄이 그릴 에이전트다. 답 줄을 빼고 마지막 줄이 맡김 질문이면 맡은 에이전트이고, 아니면 null 이라
 * 대화의 에이전트로 그린다. 알림 줄 뒤의 자동 turn 은 대화의 에이전트가 쓰므로 맡김 질문을 거슬러 찾지 않는다.
 */
export function pendingAnswerLabel(
  turns: { role: string; delegatedTo?: MentionDelegation | null }[],
): AgentLabel | null {
  const last = turns.findLast((turn) => turn.role !== "ASSISTANT");
  return last?.role === "USER" ? (last.delegatedTo?.agent ?? null) : null;
}

/**
 * 답 줄에 그릴 에이전트다. 저장된 맡김 답은 맡은 에이전트, 아직 저장되지 않은 답은 `pending` 이다. 둘 다 아니면 undefined 라
 * 대화의 에이전트로 그린다.
 */
export function answerAgentOf(
  turn: { answeredBy?: AgentLabel | null },
  pending: AgentLabel | null,
): AgentIdentity | undefined {
  const label = turn.answeredBy ?? pending;
  return label ? labelIdentity(label) : undefined;
}
