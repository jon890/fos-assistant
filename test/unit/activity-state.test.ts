import assert from "node:assert/strict";
import test from "node:test";
import { applyChatEvent, emptyActivity, failActivity, fromTree } from "../../web/src/components/chat/activity/activity-state.ts";
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

test("끝난 답의 완료되지 않은 자식은 결과를 받지 못한 것으로 보인다", () => {
  assert.deepEqual(states(fromTree(treeWithRunningChild())), [
    "tool:검색:unfinished",
    "subagent:에이전트 2:result-missing",
    "tool:읽기:unfinished",
  ]);
});

function subagentStarted(): ExecutionEventView {
  return { ...toolStarted(1, ""), eventType: "SUBAGENT_STARTED", toolName: null,
    hermesSessionId: "child-session", detail: "자료를 찾는다", model: "example-model" };
}

test("완료 사건이 없는 자식의 목표를 이름으로 쓰고 토큰과 시간을 비워 둔다", () => {
  const [item] = fromTree({ truncated: false, root: node(1, "SUCCEEDED", [subagentStarted()]) });
  assert.equal(item.state, "result-missing");
  assert.equal(item.name, "자료를 찾는다");
  assert.equal(item.inputTokens, null);
  assert.equal(item.outputTokens, null);
  assert.equal(item.durationMs, null);
});

test("사용자가 중지한 답의 완료되지 않은 자식만 중지됨으로 보인다", () => {
  const tree = { truncated: false, root: node(1, "CANCELLED", [subagentStarted()]) };
  assert.equal(fromTree(tree)[0].state, "stopped");
  tree.root.status = "SUCCEEDED";
  assert.equal(fromTree(tree, { cancelled: true })[0].state, "stopped");
});

test("중지 사건과 취소된 자식 실행도 중지됨으로 보인다", () => {
  const tree = { truncated: false, root: node(1, "SUCCEEDED", [subagentStarted(),
    { ...toolStarted(2, ""), eventType: "RUN_CANCELLED" as const }]) };
  assert.equal(fromTree(tree)[0].state, "stopped");
  const child = node(2, "CANCELLED", [subagentStarted()]);
  const items = fromTree({ truncated: false, root: node(1, "RUNNING", [], [child]) }, { running: true });
  assert.ok(items.every((item) => item.state === "stopped"));
});

test("완료를 받은 자식은 부모가 중지되어도 완료와 실제 토큰 0을 유지한다", () => {
  const started = subagentStarted();
  const completed = { ...started, sequence: 2, eventType: "SUBAGENT_COMPLETED" as const,
    inputTokens: 0, outputTokens: 0, durationMs: 1000 };
  const [item] = fromTree({ truncated: false, root: node(1, "CANCELLED", [started, completed]) });
  assert.equal(item.state, "done");
  assert.equal(item.inputTokens, 0);
  assert.equal(item.outputTokens, 0);
});

test("스트림 종료와 오류는 자식의 결과 누락이고 사용자 중지는 중지됨이다", () => {
  const state = applyChatEvent(emptyActivity(0), { type: "subagent", phase: "started", goal: "자료를 찾는다" });
  assert.equal(applyChatEvent(state, { type: "done" }).items[0].state, "result-missing");
  assert.equal(failActivity(state, 1000).items[0].state, "result-missing");
  assert.equal(applyChatEvent(state, { type: "stopped" }).items[0].state, "stopped");
});

test("이름이 있으면 이름을 쓰고 없으면 긴 목표나 preview를 줄인다", () => {
  const event = subagentStarted();
  event.subagentName = "조사 담당";
  assert.equal(fromTree({ truncated: false, root: node(1, "SUCCEEDED", [event]) })[0].name, "조사 담당");
  event.subagentName = null;
  event.detail = "긴 목표 ".repeat(40);
  const [item] = fromTree({ truncated: false, root: node(1, "SUCCEEDED", [event]) });
  assert.ok(item.name.endsWith("…"));
  assert.ok(item.name.length <= 80);
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
