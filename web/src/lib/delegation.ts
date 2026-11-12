/**
 * 맡긴 줄과 결과 도착 줄, 승인 카드의 이름표 값이다(docs/features/agent-team/design.md 의 「PR2 협업 보이기」).
 */
import type { AgentIdentity } from "@/components/agent/agent-badge";
import { agentLabel } from "./format.ts";
import { withParticle } from "./korean-particle.ts";

/**
 * 이름표 한 벌이다. `AgentLabel` 을 그대로 받는다. 지운 에이전트는 네 칸이 모두 null 이다.
 */
export type AgentLabel = {
  code: string | null;
  name: string | null;
  icon: string | null;
  color: string | null;
};

/** 대화의 turn 이 바로 맡긴 위임 하나다. `DelegationLine` 을 그대로 받는다 */
export type DelegationLine = {
  executionId: number;
  /** 맡긴 turn 의 실행 번호다. 이 번호의 답 아래에 줄을 그린다 */
  parentExecutionId: number;
  status: "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
  agent: AgentLabel;
};

const STATUS_LABEL: Record<DelegationLine["status"], string> = {
  RUNNING: "진행 중",
  SUCCEEDED: "끝남",
  FAILED: "실패",
  CANCELLED: "멈춤",
};

/** 맡긴 줄의 상태 글이다 */
export function delegationStatusLabel(
  status: DelegationLine["status"],
): string {
  return STATUS_LABEL[status] ?? status;
}

/** 이름표 부품에 넘길 값이다. 지운 에이전트는 「지운 에이전트」 와 회색이다 */
export function labelIdentity(
  label: AgentLabel | null | undefined,
): AgentIdentity {
  if (!label || label.name === null)
    return { name: agentLabel(null), code: null };
  return {
    name: label.name,
    code: label.code,
    icon: label.icon,
    color: label.color,
  };
}

/** 결과 도착 줄 한 줄의 글이다. 「쇼핑 담당이 끝냈어요」 */
export function resultArrivalText(line: DelegationLine): string {
  const name = withParticle(labelIdentity(line.agent).name, "이", "가");
  return line.status === "FAILED"
    ? `${name} 하지 못했어요`
    : `${name} 끝냈어요`;
}

/** 승인 카드의 요청한 에이전트 글이다. 「쇼핑 담당이 요청」 */
export function requestedByText(label: AgentLabel): string {
  return `${withParticle(labelIdentity(label).name, "이", "가")} 요청`;
}

/**
 * 이력의 맡긴 줄과 사건으로 받은 줄을 합친다. 같은 실행이면 끝난 상태를 고른다. 끝난 상태는 다시 바뀌지 않으므로 늦게 온
 * 진행 중이 끝난 상태를 덮지 않는다. 실행 번호순이다.
 */
export function mergeDelegations(
  saved: DelegationLine[] | undefined,
  live: DelegationLine[],
  parentExecutionId: number | null | undefined,
): DelegationLine[] {
  if (parentExecutionId === null || parentExecutionId === undefined)
    return saved ?? [];
  const merged = new Map<number, DelegationLine>();
  for (const line of saved ?? []) merged.set(line.executionId, line);
  for (const line of live) {
    if (line.parentExecutionId !== parentExecutionId) continue;
    const known = merged.get(line.executionId);
    if (!known || known.status === "RUNNING")
      merged.set(line.executionId, line);
  }
  return [...merged.values()].sort((a, b) => a.executionId - b.executionId);
}

/** 사건으로 받은 줄을 실행 번호로 넣는다. 이미 끝난 줄을 진행 중으로 되돌리지 않는다 */
export function receiveDelegation(
  previous: DelegationLine[],
  next: DelegationLine,
): DelegationLine[] {
  const known = previous.find((line) => line.executionId === next.executionId);
  if (known && known.status !== "RUNNING") return previous;
  return [
    ...previous.filter((line) => line.executionId !== next.executionId),
    next,
  ];
}
