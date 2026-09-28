import type { ChatEvent } from "@/lib/chat-event";
import type { ExecutionTreeNode, ExecutionTreeResponse } from "@/components/execution/execution-tree";
import { subagentLabel } from "../../../lib/format.ts";

export type ActivityItemKind = "tool" | "subagent" | "step" | "switched";
export type ActivityItemState = "running" | "done" | "failed" | "stopped" | "unfinished" | "result-missing";

export type ActivityItem = {
  key: string;
  kind: ActivityItemKind;
  name: string;
  detail: string | null;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  durationMs: number | null;
  state: ActivityItemState;
  pairKey: string | null;
};

export type ActivityState = { items: ActivityItem[]; startedAt: number; endedAt: number | null };

export const STEP_LABELS: Record<string, string> = {
  chief: "정리",
  researcher: "조사",
  engineer: "구현",
  synthesizer: "합치기",
};

export function emptyActivity(startedAt: number): ActivityState {
  return { items: [], startedAt, endedAt: null };
}

export function failActivity(state: ActivityState, endedAt: number): ActivityState {
  return { ...state, endedAt, items: state.items.map((item) => item.state === "running"
    ? { ...item, state: item.kind === "subagent" ? "result-missing" : "unfinished" } : item) };
}

function append(items: ActivityItem[], item: Omit<ActivityItem, "key">): ActivityItem[] {
  return [...items, { ...item, key: `${items.length}` }];
}

function finish(items: ActivityItem[], kind: ActivityItemKind, pairKey: string | null,
  event: ChatEvent): ActivityItem[] {
  const index = items.findIndex((item) => item.kind === kind && item.state === "running"
    && (kind === "subagent" && !pairKey ? true : item.pairKey === pairKey));
  if (index < 0) {
    return append(items, {
      kind, name: kind === "tool" ? event.toolName ?? "도구" : subagentLabel(null, event.goal ?? event.detail),
      detail: kind === "tool" ? event.detail ?? null : null,
      model: event.model ?? null, inputTokens: event.inputTokens ?? null,
      outputTokens: event.outputTokens ?? null, durationMs: event.durationMs ?? null,
      state: event.failed ? "failed" : "done", pairKey,
    });
  }
  return items.map((item, position) => position === index ? {
    ...item,
    detail: kind === "tool" ? event.detail ?? item.detail : item.detail,
    model: event.model ?? item.model,
    inputTokens: event.inputTokens ?? item.inputTokens,
    outputTokens: event.outputTokens ?? item.outputTokens,
    durationMs: event.durationMs ?? item.durationMs,
    state: event.failed ? "failed" as const : "done" as const,
  } : item);
}

export function applyChatEvent(state: ActivityState, event: ChatEvent): ActivityState {
  if (event.type === "reset") return { ...state, items: [] };
  if (event.type === "done") return failActivity(state, Date.now());
  if (event.type === "stopped") return {
    ...state,
    endedAt: Date.now(),
    items: state.items.map((item) => item.state === "running" ? { ...item, state: "stopped" } : item),
  };
  const items = state.items;
  if (event.type === "tool") {
    const name = event.toolName ?? "도구";
    if (event.phase === "started") return { ...state, items: append(items, {
      kind: "tool", name, detail: event.detail ?? null, model: null, inputTokens: null,
      outputTokens: null, durationMs: null, state: "running", pairKey: name,
    }) };
    if (event.phase === "completed") return { ...state, items: finish(items, "tool", name, event) };
  }
  if (event.type === "subagent") {
    if (event.phase === "started") return { ...state, items: append(items, {
      kind: "subagent", name: subagentLabel(null, event.goal ?? event.detail), detail: null,
      model: event.model ?? null, inputTokens: null, outputTokens: null,
      durationMs: null, state: "running", pairKey: event.subagentId ?? null,
    }) };
    if (event.phase === "completed") return { ...state,
      items: finish(items, "subagent", event.subagentId ?? null, event) };
  }
  if (event.type === "step" && event.stepName && event.stepState) {
    const index = items.findIndex((item) => item.kind === "step" && item.pairKey === event.stepName);
    if (index < 0 || event.stepState === "started") return { ...state, items: append(items, {
      kind: "step", name: STEP_LABELS[event.stepName] ?? event.stepName, detail: null,
      model: null, inputTokens: null, outputTokens: null, durationMs: null,
      state: event.stepState === "failed" ? "failed" : event.stepState === "completed" ? "done" : "running",
      pairKey: event.stepName,
    }) };
    return { ...state, items: items.map((item, position) => position === index
      ? { ...item, state: event.stepState === "failed" ? "failed" : "done" } : item) };
  }
  if (event.type === "switched") return { ...state, items: append(items, {
    kind: "switched", name: `여기부터 ${event.text ?? ""} 로 돈다`, detail: null,
    model: null, inputTokens: null, outputTokens: null, durationMs: null,
    state: "done", pairKey: null,
  }) };
  return state;
}

/**
 * 실행 나무를 작업 과정 항목으로 바꾼다.
 *
 * <p>끝난 답에서 완료 사건이 없는 자식은 결과를 받지 못한 것으로 보인다. 사용자가 중지한 답만
 * 「중지됨」 으로 보이고, 다른 창에서 도는 turn 은 `running` 을 주어 그대로 둔다.
 */
export function fromTree(tree: ExecutionTreeResponse,
  options: { running?: boolean; cancelled?: boolean } = {}): ActivityItem[] {
  const cancelled = (node: ExecutionTreeNode) => node.status === "CANCELLED"
    || node.events.some((event) => event.eventType === "RUN_CANCELLED");
  const turnCancelled = options.cancelled || cancelled(tree.root);
  let state = emptyActivity(Date.parse(tree.root.startedAt));
  const visit = (node: ExecutionTreeNode, isRoot: boolean) => {
    const firstItem = state.items.length;
    const nodeCancelled = turnCancelled || cancelled(node);
    if (!isRoot && node.events.length > 0) state = { ...state, items: append(state.items, {
      kind: "subagent", name: subagentLabel(node.agentName ?? node.agentCode, null), detail: null,
      model: node.model, inputTokens: node.inputTokens, outputTokens: node.outputTokens,
      durationMs: node.latencyMs, state: node.status === "FAILED" ? "failed" :
        node.status === "SUCCEEDED" ? "done" : node.status === "CANCELLED" ? "stopped" : "running",
      pairKey: `${node.executionId}`,
    }) };
    for (const event of node.events) {
      switch (event.eventType) {
        case "TOOL_STARTED":
        case "TOOL_COMPLETED":
          state = applyChatEvent(state, { type: "tool", toolName: event.toolName,
            detail: event.detail, durationMs: event.durationMs, failed: event.failed,
            phase: event.eventType === "TOOL_STARTED" ? "started" : "completed" });
          break;
        case "SUBAGENT_STARTED":
        case "SUBAGENT_COMPLETED":
          state = applyChatEvent(state, { type: "subagent", goal: subagentLabel(event.subagentName, event.detail),
            subagentId: event.hermesSessionId,
            model: event.model, inputTokens: event.inputTokens, outputTokens: event.outputTokens,
            durationMs: event.durationMs, failed: event.failed,
            phase: event.eventType === "SUBAGENT_STARTED" ? "started" : "completed" });
          break;
        case "PROVIDER_SWITCHED":
          state = applyChatEvent(state, { type: "switched", text: event.detail });
          break;
      }
    }
    state = { ...state, items: state.items.map((item, index) => index < firstItem || item.state !== "running"
      || (options.running && !nodeCancelled) ? item : { ...item,
        state: nodeCancelled ? "stopped" : item.kind === "subagent" ? "result-missing" : "unfinished" }) };
    node.children.forEach((child) => visit(child, false));
  };
  visit(tree.root, true);
  return state.items;
}
