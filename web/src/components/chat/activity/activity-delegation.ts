import type { ChatEvent } from "@/lib/chat-event";
import { labelIdentity } from "../../../lib/delegation.ts";
import { subagentLabel } from "../../../lib/format.ts";
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
