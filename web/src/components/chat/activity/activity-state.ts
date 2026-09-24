import type { ChatEvent } from "@/lib/chat-event";
import type { ExecutionTreeNode, ExecutionTreeResponse } from "@/components/execution/execution-tree";

export type ActivityItemKind = "tool" | "subagent" | "step" | "switched";
export type ActivityItemState = "running" | "done" | "failed" | "stopped";

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

export type ActivityState = { items: ActivityItem[]; startedAt: number };

export const STEP_LABELS: Record<string, string> = {
  chief: "정리",
  researcher: "조사",
  engineer: "구현",
  synthesizer: "합치기",
};

export function emptyActivity(startedAt: number): ActivityState {
  return { items: [], startedAt };
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
      kind, name: kind === "tool" ? event.toolName ?? "도구" : event.goal ?? "하위 에이전트",
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
      kind: "subagent", name: event.goal ?? "하위 에이전트", detail: null,
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

export function fromTree(tree: ExecutionTreeResponse): ActivityItem[] {
  let state = emptyActivity(Date.parse(tree.root.startedAt));
  const visit = (node: ExecutionTreeNode, isRoot: boolean) => {
    if (!isRoot) state = { ...state, items: append(state.items, {
      kind: "subagent", name: node.agentName ?? node.agentCode ?? "하위 에이전트", detail: null,
      model: node.model, inputTokens: node.inputTokens, outputTokens: node.outputTokens,
      durationMs: node.latencyMs, state: node.status === "FAILED" ? "failed" :
        node.status === "CANCELLED" ? "stopped" : "done", pairKey: `${node.executionId}`,
    }) };
    for (const event of node.events) {
      switch (event.eventType) {
        case "TOOL_STARTED":
        case "TOOL_COMPLETED":
          state = applyChatEvent(state, { type: "tool", toolName: event.toolName,
            detail: event.detail, durationMs: event.durationMs,
            phase: event.eventType === "TOOL_STARTED" ? "started" : "completed" });
          break;
        case "SUBAGENT_STARTED":
        case "SUBAGENT_COMPLETED":
          state = applyChatEvent(state, { type: "subagent", goal: event.detail,
            model: event.model, inputTokens: event.inputTokens, outputTokens: event.outputTokens,
            durationMs: event.durationMs,
            phase: event.eventType === "SUBAGENT_STARTED" ? "started" : "completed" });
          break;
        case "PROVIDER_SWITCHED":
          state = applyChatEvent(state, { type: "switched", text: event.detail });
          break;
      }
    }
    node.children.forEach((child) => visit(child, false));
  };
  visit(tree.root, true);
  return state.items.map((item) => item.state === "running" ? { ...item, state: "stopped" } : item);
}

export function countOf(items: ActivityItem[]): { toolCount: number; subagentCount: number } {
  return {
    toolCount: items.filter((item) => item.kind === "tool").length,
    subagentCount: items.filter((item) => item.kind === "subagent").length,
  };
}
