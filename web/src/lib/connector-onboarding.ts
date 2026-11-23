import type { AgentView } from "@/lib/agent";
import type { BindingStatus, BoundAgent } from "@/lib/connection";

/** 연결을 붙일 후보 한 줄의 상태다. 붙일 수 있다, 이미 붙었다, 그룹에 공개해 붙일 수 없다 가운데 하나다. */
export type AgentChoiceState = "BINDABLE" | "BOUND" | "GROUP";

export type AgentChoice = {
  code: string;
  name: string;
  state: AgentChoiceState;
  /** 에이전트 화면에서 이 연결을 하러 왔을 때 그 에이전트다. */
  preferred: boolean;
};

const STATE_ORDER: Record<AgentChoiceState, number> = {
  BINDABLE: 0,
  BOUND: 1,
  GROUP: 2,
};

/**
 * 연결을 마친 뒤 고를 수 있는 내 에이전트 목록을 만든다.
 *
 * <p>붙이기는 주인만 한다. 그룹에 공개한 에이전트는
 * 붙일 수 없지만 까닭을 보이려고 남긴다. 순서는 `preferred` 가 맨 앞이고, 그다음 붙일 수 있는 것, 붙은 것, 그룹
 * 공개 순서다. 같은 상태 안에서는 받은 순서를 지킨다.
 */
export function agentChoices(
  agents: readonly AgentView[],
  bindings: readonly BoundAgent[],
  preferred: string | null,
): AgentChoice[] {
  const bound = new Set(bindings.map((binding) => binding.agentCode));
  // 기본 비서에는 연결을 붙이지 않는다. 비서는 전문 에이전트에게 맡긴다(docs/features/assistant-hub/spec.md 의 REQ9).
  return agents
    .filter((agent) => agent.ownedByMe && agent.primary !== true)
    .map((agent): AgentChoice => {
      const state: AgentChoiceState = bound.has(agent.code)
        ? "BOUND"
        : agent.visibility === "GROUP"
          ? "GROUP"
          : "BINDABLE";
      return {
        code: agent.code,
        name: agent.name,
        state,
        preferred: agent.code === preferred,
      };
    })
    .sort(
      (left, right) =>
        Number(right.preferred) - Number(left.preferred) ||
        STATE_ORDER[left.state] - STATE_ORDER[right.state],
    );
}

/**
 * 붙인 직후 언제 쓸 수 있는지를 화면 말로 바꾼다. 반영은 모두 자동이고 관리자를 기다린다고 말하지 않는다.
 * 자동 반영이 실패했으면 그 사실을 알리고, 재시작 대기는 대화가 없는 틈을 기다려 조금 걸린다고 알린다.
 * 옛 응답에는 `applyFailed` 가 없어 실패가 아닌 것으로 본다.
 */
export function boundReadiness(view: {
  status: BindingStatus | null;
  restartRequired: boolean;
  applyFailed?: boolean;
}): string {
  if (view.applyFailed) return "반영하지 못했어요. 관리자에게 알렸어요.";
  if (view.restartRequired)
    return "자동으로 반영하고 있어요. 대화가 없는 틈에 반영돼서 조금 걸릴 수 있어요.";
  return view.status === "READY"
    ? "바로 쓸 수 있어요."
    : "몇 분 안에 자동으로 반영돼요.";
}
