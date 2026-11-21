import assert from "node:assert/strict";
import test from "node:test";
import {
  ExecutionTreeLoadError,
  loadExecutionTree,
} from "../../src/lib/execution-tree-load.ts";

const TREE = {
  truncated: false,
  root: {
    truncated: false,
    executionId: 7,
    agentCode: null,
    agentName: "커리어",
    status: "SUCCEEDED",
    model: null,
    inputTokens: null,
    outputTokens: null,
    estimatedCostMicros: null,
    latencyMs: 1,
    startedAt: "2026-11-21T00:00:00Z",
    events: [],
    children: [],
  },
};

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/** 정해 둔 응답을 차례로 돌려주고 부른 횟수를 센다. */
function sequence(...answers: Array<() => Response>) {
  let calls = 0;
  return {
    get calls() {
      return calls;
    },
    fetchTree: async () => {
      const answer = answers[Math.min(calls, answers.length - 1)];
      calls += 1;
      return answer();
    },
  };
}

const noWait = async () => {};

test("loadExecutionTree 는 첫 읽기가 502 로 실패하면 한 번 다시 읽어 트리를 돌려준다", async (t) => {
  t.mock.method(console, "warn", () => {});
  const server = sequence(
    () => json(502, { code: "INTERNAL_ERROR", message: "x" }),
    () => json(200, TREE),
  );

  const tree = await loadExecutionTree(7, { fetchTree: server.fetchTree, wait: noWait });

  assert.equal(tree.root.executionId, 7);
  assert.equal(server.calls, 2);
});

test("loadExecutionTree 는 연결이 끊긴 첫 읽기도 다시 읽는다", async (t) => {
  t.mock.method(console, "warn", () => {});
  let calls = 0;
  const tree = await loadExecutionTree(7, {
    fetchTree: async () => {
      calls += 1;
      if (calls === 1) throw new TypeError("Failed to fetch");
      return json(200, TREE);
    },
    wait: noWait,
  });

  assert.equal(tree.root.executionId, 7);
});

test("loadExecutionTree 는 두 번 모두 실패하면 HTTP 상태와 code 를 까닭으로 던지고 콘솔에 남긴다", async (t) => {
  const warn = t.mock.method(console, "warn", () => {});
  const server = sequence(() => json(504, { code: "INTERNAL_ERROR" }));

  await assert.rejects(
    loadExecutionTree(7, { fetchTree: server.fetchTree, wait: noWait }),
    (error: unknown) =>
      error instanceof ExecutionTreeLoadError && error.reason === "http 504 INTERNAL_ERROR",
  );
  assert.equal(server.calls, 2);
  assert.equal(warn.mock.callCount(), 2);
  assert.deepEqual(warn.mock.calls[1].arguments, [
    "작업 과정 다시 읽기 실패 executionId=7",
    "http 504 INTERNAL_ERROR",
  ]);
});

test("loadExecutionTree 는 JSON 이 아닌 본문과 트리 모양이 아닌 본문을 파싱 실패로 구분한다", async (t) => {
  t.mock.method(console, "warn", () => {});
  const html = sequence(() => new Response("<html>gateway</html>", { status: 200 }));
  await assert.rejects(
    loadExecutionTree(7, { fetchTree: html.fetchTree, wait: noWait }),
    (error: unknown) => error instanceof ExecutionTreeLoadError && error.reason === "parse body",
  );

  const empty = sequence(() => json(200, {}));
  await assert.rejects(
    loadExecutionTree(7, { fetchTree: empty.fetchTree, wait: noWait }),
    (error: unknown) => error instanceof ExecutionTreeLoadError && error.reason === "parse shape",
  );
});

test("loadExecutionTree 는 기다리는 사이 화면이 사라졌으면 다시 읽지 않는다", async (t) => {
  t.mock.method(console, "warn", () => {});
  const server = sequence(() => json(502, {}), () => json(200, TREE));

  await assert.rejects(
    loadExecutionTree(7, { fetchTree: server.fetchTree, wait: noWait, stopped: () => true }),
    ExecutionTreeLoadError,
  );
  assert.equal(server.calls, 1);
});
