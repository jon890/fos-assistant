import assert from "node:assert/strict";
import test from "node:test";
import { fromTree } from "../../web/src/components/chat/activity/activity-state.ts";
import type {
  ExecutionEventView,
  ExecutionTreeNode,
  ExecutionTreeResponse,
} from "../../web/src/components/execution/execution-tree.tsx";

function toolStarted(sequence: number, toolName: string): ExecutionEventView {
  return {
    sequence, eventType: "TOOL_STARTED", toolName, subagentName: null, hermesSessionId: null,
    durationMs: null, failed: null, detail: null, model: null, inputTokens: null, outputTokens: null,
    occurredAt: "2026-09-28T00:00:01Z",
  };
}

function node(executionId: number, status: string, events: ExecutionEventView[],
  children: ExecutionTreeNode[] = []): ExecutionTreeNode {
  return {
    truncated: false, executionId, agentCode: null, agentName: `에이전트 ${executionId}`, status,
    model: null, inputTokens: null, outputTokens: null, estimatedCostMicros: null, latencyMs: null,
    startedAt: "2026-09-28T00:00:00Z", events, children,
  };
}

/** 뿌리는 끝났고 자식 노드와 그 안의 도구는 아직 돈다. */
function treeWithRunningChild(): ExecutionTreeResponse {
  return {
    truncated: false,
    root: node(1, "SUCCEEDED", [toolStarted(1, "검색")], [
      node(2, "RUNNING", [toolStarted(2, "읽기")]),
    ]),
  };
}

function states(items: ReturnType<typeof fromTree>) {
  return items.map((item) => `${item.kind}:${item.name}:${item.state}`);
}

test("선택지 없이 읽으면 도는 항목을 모두 중지됨으로 보인다", () => {
  assert.deepEqual(states(fromTree(treeWithRunningChild())), [
    "tool:검색:stopped",
    "subagent:에이전트 2:stopped",
    "tool:읽기:stopped",
  ]);
});

test("도는 나무로 읽으면 도는 자식 노드와 도구를 그대로 둔다", () => {
  assert.deepEqual(states(fromTree(treeWithRunningChild(), { running: true })), [
    "tool:검색:running",
    "subagent:에이전트 2:running",
    "tool:읽기:running",
  ]);
});

test("도는 나무로 읽어도 끝난 자식 노드는 끝난 상태로 보인다", () => {
  const tree: ExecutionTreeResponse = {
    truncated: false,
    root: node(1, "RUNNING", [], [
      node(2, "SUCCEEDED", [toolStarted(1, "검색")]),
      node(3, "FAILED", [toolStarted(2, "읽기")]),
    ]),
  };
  const items = fromTree(tree, { running: true }).filter((item) => item.kind === "subagent");
  assert.deepEqual(states(items), ["subagent:에이전트 2:done", "subagent:에이전트 3:failed"]);
});
