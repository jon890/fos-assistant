import type { ChatEvent } from "@/lib/chat-event";
import { labelIdentity } from "../../../lib/delegation.ts";
import { subagentLabel } from "../../../lib/format.ts";
import { bareName } from "../../../lib/tool-label.ts";
import type { ActivityItem, ActivityState } from "./activity-state";

/**
 * 맡긴 실행의 도구 사건(`delegation_activity`)을 도는 답의 작업 과정에 넣는다(스펙 assistant-hub REQ3).
 *
 * <p>맡은 실행 번호를 열쇠로 「도우미」 줄을 하나 두고, 그 실행의 도구 줄을 도우미 줄과 그 아래 줄들 바로 뒤에 넣는다.
 * 끝난 답을 다시 그리는 `fromTree` 가 같은 번호를 도우미 줄의 `pairKey` 로 쓰므로 새로 고쳐도 같은 줄이 된다.
 * 도우미 줄과 그 도구 줄은 `delegated` 라 답이 끝날 때 아직 돌면 「맡겨 둠」 이 된다.
 */
export function applyDelegationActivity(
  state: ActivityState,
  event: ChatEvent,
): ActivityState {
  const executionId = event.executionId;
  if (executionId == null || state.endedAt !== null) return state;
  const parent = String(executionId);
  const name = event.toolName ?? "도구";
  let items = state.items;
  if (
    !items.some((item) => item.kind === "subagent" && item.pairKey === parent)
  )
    items = [
      ...items,
      item(items, {
        kind: "subagent",
        name: subagentLabel(
          event.delegation ? labelIdentity(event.delegation.agent).name : null,
          null,
        ),
        connector: null,
        detail: null,
        state: "running",
        pairKey: parent,
        delegated: true,
      }),
    ];
  const pairKey = `${parent}:${name}`;
  if (event.phase === "completed") {
    const index = items.findIndex(
      (entry) =>
        entry.parent === parent &&
        entry.pairKey === pairKey &&
        entry.state === "running",
    );
    if (index >= 0)
      return {
        ...state,
        items: items.map((entry, position) =>
          position === index
            ? {
                ...entry,
                // 끝 사건의 빈 `detail` 은 시작 사건의 대상(검색어)을 지우지 않는다.
                detail: event.detail || entry.detail,
                connector: entry.connector ?? event.connector ?? null,
                durationMs: event.durationMs ?? entry.durationMs,
                state: event.failed ? "failed" : "done",
              }
            : entry,
        ),
      };
  }
  const tool = item(items, {
    kind: "tool",
    name,
    connector: event.connector ?? null,
    detail: event.detail ?? null,
    state:
      event.phase === "completed"
        ? event.failed
          ? "failed"
          : "done"
        : "running",
    pairKey,
    delegated: true,
    parent,
  });
  if (event.phase === "completed") tool.durationMs = event.durationMs ?? null;
  const helper = items.findIndex(
    (entry) => entry.kind === "subagent" && entry.pairKey === parent,
  );
  let at = helper + 1;
  while (at < items.length && items[at].parent === parent) at += 1;
  return {
    ...state,
    items: [...items.slice(0, at), tool, ...items.slice(at)],
  };
}

function item(
  items: ActivityItem[],
  fields: Pick<
    ActivityItem,
    "kind" | "name" | "connector" | "detail" | "state" | "pairKey"
  > &
    Partial<Pick<ActivityItem, "delegated" | "parent">>,
): ActivityItem {
  return {
    key: `delegated-${items.length}-${fields.pairKey}`,
    model: null,
    inputTokens: null,
    outputTokens: null,
    durationMs: null,
    ...fields,
  };
}

/** 실행 번호가 정해지기 전에 붙잡아 두는 사건 수의 상한이다. 넘으면 오래된 것부터 버린다 */
const HELD_MAX = 100;

/**
 * 맡긴 실행의 도구 사건을 지금 도는 답에 넣을지 정한다. 넣을 사건이면 돌려주고 아니면 null 이다.
 *
 * <p>사건의 맡긴 답(`delegation.parentExecutionId`)이 지금 도는 답의 실행 번호와 같을 때만 넣는다. 실행 번호가 아직 정해지지
 * 않았으면 `held` 에 붙잡아 두고 {@link takeHeldDelegationActivity} 가 `started` 뒤에 꺼낸다. 앞 답이 맡겨 둔 실행의 사건이
 * 새 답에 섞이지 않게 하려는 것이다.
 */
export function routeDelegationActivity(
  held: ChatEvent[],
  running: number | null,
  event: ChatEvent,
): ChatEvent | null {
  const parent = event.delegation?.parentExecutionId ?? null;
  if (running === null) {
    held.push(event);
    if (held.length > HELD_MAX) held.splice(0, held.length - HELD_MAX);
    return null;
  }
  return parent === running ? event : null;
}

/** 실행 번호가 정해진 뒤 붙잡아 둔 사건 가운데 그 답의 것만 차례로 꺼내고 나머지는 버린다 */
export function takeHeldDelegationActivity(
  held: ChatEvent[],
  running: number | null,
): ChatEvent[] {
  const taken = held.filter(
    (event) =>
      running !== null && event.delegation?.parentExecutionId === running,
  );
  held.length = 0;
  return taken;
}

/**
 * 도는 답이 맡긴 줄(`delegation` 사건)을 받으면 아직 이름이 없는 맡기기 도구 줄 가운데 가장 먼저 시작한 줄에 맡은 에이전트 이름을 붙인다.
 *
 * <p>맡기기 도구가 도는 동안 맡긴 실행이 생기고 Control Plane 이 그 에이전트로 맡긴 줄을 보낸다. 이름은 모델의 인자가 아니라 그 줄에서
 * 받는다(스펙 assistant-hub DECISION30). 이름이 없는 줄이 없으면 그대로다.
 */
export function applyDelegationName(
  state: ActivityState,
  agentName: string | null | undefined,
): ActivityState {
  const name = agentName?.trim();
  if (!name) return state;
  const index = state.items.findIndex(
    (item) =>
      item.kind === "tool" &&
      item.parent === undefined &&
      bareName(item.name) === "agent_delegate" &&
      item.delegatedAgentName === undefined,
  );
  if (index < 0) return state;
  return {
    ...state,
    items: state.items.map((item, position) =>
      position === index ? { ...item, delegatedAgentName: name } : item,
    ),
  };
}
