import type { ChatEvent } from "@/lib/chat-event";
import type {
  ExecutionTreeNode,
  ExecutionTreeResponse,
} from "@/components/execution/execution-tree";
import { subagentLabel } from "../../../lib/format.ts";
import { toolLabel, type ConnectorToolName } from "../../../lib/tool-label.ts";
import type { ActivityOutcome, ActivityUse } from "../../../lib/tool-kind.ts";
import { interimKey } from "../turn-stream-text.ts";
import { applyDelegationActivity } from "./activity-delegation.ts";

export type ActivityItemKind = "tool" | "subagent" | "interim";
export type ActivityItemState =
  | "running"
  | "done"
  | "failed"
  | "stopped"
  | "unfinished"
  | "result-missing"
  /** 답이 끝날 때 아직 도는 맡긴 실행이다. 결과는 다음 답으로 온다 */
  | "handed-off";

export type ActivityItem = {
  key: string;
  kind: ActivityItemKind;
  name: string;
  /** 플러그인 도구의 서비스 이름과 도구 제목. 그 밖의 항목은 null */
  connector: ConnectorToolName | null;
  detail: string | null;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  durationMs: number | null;
  state: ActivityItemState;
  pairKey: string | null;
  /** 다른 에이전트에게 맡긴 실행의 도우미 줄과 그 도구 줄이다. 답이 끝나도 실패로 세지 않는다 */
  delegated?: boolean;
  /** 맡긴 실행의 도구 줄이면 그 도우미 줄의 `pairKey`(맡은 실행 번호)다 */
  parent?: string;
  /** `agent_delegate` 도구 줄의 맡은 에이전트 이름이다. Control Plane 이 보낸 맡김 줄이나 실행 트리에서 받는다 */
  delegatedAgentName?: string;
};

export type ActivityState = {
  items: ActivityItem[];
  startedAt: number;
  endedAt: number | null;
};

/** 항목이 화면에 보이는 문장이다. 도구는 그리는 때 정해 도는 중과 끝남의 말이 다르다. */
export function activityLabel(item: ActivityItem): string {
  return item.kind === "tool"
    ? toolLabel(
        item.name,
        item.state === "running",
        item.connector,
        item.delegatedAgentName,
      )
    : item.name;
}

export type { ActivityOutcome };

/** 흘러온 항목을 접힌 줄이 잇는 「쓴 것」 으로 바꾼다. 비서 안내는 쓴 것이 아니다. */
export function activityUses(items: ActivityItem[]): ActivityUse[] {
  return items.flatMap((item): ActivityUse[] =>
    item.kind === "tool"
      ? [{ kind: "TOOL", toolName: item.name, connector: item.connector }]
      : item.kind === "subagent"
        ? [{ kind: "SUBAGENT" }]
        : [],
  );
}

/** 흘러온 사건으로 끝난 블록이 어떻게 끝났는지 고른다. 중지가 오류보다 먼저다. */
export function activityOutcome(items: ActivityItem[]): ActivityOutcome {
  if (items.some((item) => item.state === "stopped")) return "stopped";
  if (
    items.some(
      (item) => item.state === "unfinished" || item.state === "result-missing",
    )
  )
    return "failed";
  return "done";
}

export function emptyActivity(startedAt: number): ActivityState {
  return { items: [], startedAt, endedAt: null };
}

export function failActivity(
  state: ActivityState,
  endedAt: number,
): ActivityState {
  return {
    ...state,
    endedAt,
    items: state.items
      .filter((item) => item.kind !== "interim")
      .map((item) =>
        item.state === "running"
          ? {
              ...item,
              state: item.delegated
                ? "handed-off"
                : item.kind === "subagent"
                  ? "result-missing"
                  : "unfinished",
            }
          : item,
      ),
  };
}

function append(
  items: ActivityItem[],
  item: Omit<ActivityItem, "key">,
): ActivityItem[] {
  return [...items, { ...item, key: `${items.length}` }];
}

function finish(
  items: ActivityItem[],
  kind: ActivityItemKind,
  pairKey: string | null,
  event: ChatEvent,
): ActivityItem[] {
  const index = items.findIndex(
    (item) =>
      item.kind === kind &&
      item.state === "running" &&
      (kind === "subagent" && !pairKey ? true : item.pairKey === pairKey),
  );
  if (index < 0) {
    return append(items, {
      kind,
      name:
        kind === "tool"
          ? (event.toolName ?? "도구")
          : subagentLabel(null, event.goal ?? event.detail),
      connector: kind === "tool" ? (event.connector ?? null) : null,
      detail: kind === "tool" ? (event.detail ?? null) : null,
      model: event.model ?? null,
      inputTokens: event.inputTokens ?? null,
      outputTokens: event.outputTokens ?? null,
      durationMs: event.durationMs ?? null,
      state: event.failed ? "failed" : "done",
      pairKey,
      delegatedAgentName: event.delegatedAgentName ?? undefined,
    });
  }
  return items.map((item, position) =>
    position === index
      ? {
          ...item,
          connector: item.connector ?? event.connector ?? null,
          delegatedAgentName:
            item.delegatedAgentName ?? event.delegatedAgentName ?? undefined,
          // 끝 사건의 빈 `detail` 은 시작 사건의 대상(검색어)을 지우지 않는다.
          detail: kind === "tool" ? event.detail || item.detail : item.detail,
          model: event.model ?? item.model,
          inputTokens: event.inputTokens ?? item.inputTokens,
          outputTokens: event.outputTokens ?? item.outputTokens,
          durationMs: event.durationMs ?? item.durationMs,
          state: event.failed ? ("failed" as const) : ("done" as const),
        }
      : item,
  );
}

export function applyChatEvent(
  state: ActivityState,
  event: ChatEvent,
): ActivityState {
  if (event.type === "reset") return { ...state, items: [] };
  if (event.type === "done") return failActivity(state, Date.now());
  if (event.type === "stopped")
    return {
      ...state,
      endedAt: Date.now(),
      items: state.items
        .filter((item) => item.kind !== "interim")
        .map((item) =>
          item.state === "running" ? { ...item, state: "stopped" } : item,
        ),
    };
  const items = state.items;
  if (
    event.type === "interim" &&
    event.text?.trim() &&
    state.endedAt === null
  ) {
    const pairKey = interimKey(event);
    if (
      items.some((item) => item.kind === "interim" && item.pairKey === pairKey)
    )
      return state;
    return {
      ...state,
      items: append(items, {
        kind: "interim",
        name: event.text,
        connector: null,
        detail: null,
        model: null,
        inputTokens: null,
        outputTokens: null,
        durationMs: null,
        state: "done",
        pairKey,
      }),
    };
  }
  if (event.type === "tool") {
    const name = event.toolName ?? "도구";
    if (event.phase === "started")
      return {
        ...state,
        items: append(items, {
          kind: "tool",
          name,
          connector: event.connector ?? null,
          detail: event.detail ?? null,
          model: null,
          inputTokens: null,
          outputTokens: null,
          durationMs: null,
          state: "running",
          pairKey: name,
          delegatedAgentName: event.delegatedAgentName ?? undefined,
        }),
      };
    if (event.phase === "completed")
      return { ...state, items: finish(items, "tool", name, event) };
  }
  if (event.type === "subagent") {
    if (event.phase === "started")
      return {
        ...state,
        items: append(items, {
          kind: "subagent",
          name: subagentLabel(null, event.goal ?? event.detail),
          connector: null,
          detail: null,
          model: event.model ?? null,
          inputTokens: null,
          outputTokens: null,
          durationMs: null,
          state: "running",
          pairKey: event.subagentId ?? null,
        }),
      };
    if (event.phase === "completed")
      return {
        ...state,
        items: finish(items, "subagent", event.subagentId ?? null, event),
      };
  }
  // 모델이 바뀐 사건(`switched`)은 대화의 작업 과정에 줄을 만들지 않는다. 관리자 영역의 실행 상세만 보인다.
  return state;
}

/**
 * 실행 트리를 작업 과정 항목으로 바꾼다.
 *
 * <p>끝난 답에서 완료 사건이 없는 자식은 결과를 받지 못한 것으로 보인다. 사용자가 중지한 답만
 * 「중지됨」 으로 보이고, 다른 창에서 도는 turn 은 `running` 을 주어 그대로 둔다.
 */
export function fromTree(
  tree: ExecutionTreeResponse,
  options: { running?: boolean; cancelled?: boolean } = {},
): ActivityItem[] {
  const cancelled = (node: ExecutionTreeNode) =>
    node.status === "CANCELLED" ||
    node.events.some((event) => event.eventType === "RUN_CANCELLED");
  const turnCancelled = options.cancelled || cancelled(tree.root);
  let state = emptyActivity(Date.parse(tree.root.startedAt));
  const visit = (node: ExecutionTreeNode, isRoot: boolean) => {
    const firstItem = state.items.length;
    const nodeCancelled = turnCancelled || cancelled(node);
    // 답이 끝난 뒤에도 도는 맡긴 실행은 실패가 아니라 결과를 다음 답으로 넘긴 것이다
    const handedOff =
      !isRoot && node.delegated === true && node.status === "RUNNING";
    if (!isRoot && node.events.length > 0)
      state = {
        ...state,
        items: append(state.items, {
          kind: "subagent",
          name: subagentLabel(node.agentName ?? node.agentCode, null),
          connector: null,
          detail: null,
          model: node.model,
          inputTokens: node.inputTokens,
          outputTokens: node.outputTokens,
          durationMs: node.latencyMs,
          state:
            node.status === "FAILED"
              ? "failed"
              : node.status === "SUCCEEDED"
                ? "done"
                : node.status === "CANCELLED"
                  ? "stopped"
                  : "running",
          pairKey: `${node.executionId}`,
          delegated: node.delegated === true ? true : undefined,
        }),
      };
    for (const event of node.events) {
      switch (event.eventType) {
        case "TOOL_STARTED":
        case "TOOL_COMPLETED":
          // 맡긴 실행의 도구 줄은 그 도우미 아래에 두고 같은 실행 안에서만 시작과 끝을 짝짓는다
          if (!isRoot && node.delegated === true) {
            state = applyDelegationActivity(state, {
              type: "delegation_activity",
              executionId: node.executionId,
              toolName: event.toolName,
              connector: event.connector ?? null,
              delegatedAgentName: event.delegatedAgentName ?? null,
              detail: event.detail,
              durationMs: event.durationMs,
              failed: event.failed,
              phase:
                event.eventType === "TOOL_STARTED" ? "started" : "completed",
            });
            break;
          }
          state = applyChatEvent(state, {
            type: "tool",
            toolName: event.toolName,
            connector: event.connector ?? null,
            delegatedAgentName: event.delegatedAgentName ?? null,
            detail: event.detail,
            durationMs: event.durationMs,
            failed: event.failed,
            phase: event.eventType === "TOOL_STARTED" ? "started" : "completed",
          });
          break;
        case "SUBAGENT_STARTED":
        case "SUBAGENT_COMPLETED":
          state = applyChatEvent(state, {
            type: "subagent",
            // 진행 중 줄과 같게 목표를 먼저 쓴다. 이름 칸에는 Hermes 의 id 가 올 수 있어 목표가 없을 때만 쓴다.
            goal: subagentLabel(
              event.detail?.trim() ? null : event.subagentName,
              event.detail,
            ),
            subagentId: event.hermesSessionId,
            model: event.model,
            inputTokens: event.inputTokens,
            outputTokens: event.outputTokens,
            durationMs: event.durationMs,
            failed: event.failed,
            phase:
              event.eventType === "SUBAGENT_STARTED" ? "started" : "completed",
          });
          break;
      }
    }
    state = {
      ...state,
      items: state.items.map((item, index) =>
        index < firstItem ||
        item.state !== "running" ||
        (options.running && !nodeCancelled)
          ? item
          : {
              ...item,
              state: nodeCancelled
                ? "stopped"
                : handedOff
                  ? "handed-off"
                  : item.kind === "subagent"
                    ? "result-missing"
                    : "unfinished",
            },
      ),
    };
    node.children.forEach((child) => visit(child, false));
  };
  visit(tree.root, true);
  return state.items;
}
