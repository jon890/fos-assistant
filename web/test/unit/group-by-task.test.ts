import assert from "node:assert/strict";
import test from "node:test";
import { groupByTask } from "../../web/src/components/shell/group-by-task.ts";

type Row = Parameters<typeof groupByTask>[0][number];

function row(id: string, updatedAt: string, task: { id: string; title: string } | null): Row {
  return {
    id,
    title: id,
    agentCode: "a",
    agentName: "A",
    updatedAt,
    provider: null,
    model: null,
    reasoningEffort: null,
    taskId: task?.id ?? null,
    taskTitle: task?.title ?? null,
  };
}

const T1 = { id: "task-1", title: "아침 요약" };
const T2 = { id: "task-2", title: "주간 정리" };

test("작업은 가장 최근 대화 순이고 보통 대화는 others 에 남는다", () => {
  const { tasks, others } = groupByTask([
    row("c1", "2026-10-04T09:00:00Z", null),
    row("t1-old", "2026-10-01T09:00:00Z", T1),
    row("t2-new", "2026-10-03T09:00:00Z", T2),
    row("c2", "2026-10-02T09:00:00Z", null),
    row("t1-new", "2026-10-02T09:00:00Z", T1),
    row("c3", "2026-09-01T09:00:00Z", null),
  ]);
  assert.deepEqual(
    tasks.map((group) => group.taskId),
    ["task-2", "task-1"],
  );
  assert.equal(tasks[1].title, "아침 요약");
  assert.deepEqual(
    tasks[1].conversations.map((item) => item.id),
    ["t1-new", "t1-old"],
  );
  assert.deepEqual(
    others.map((item) => item.id),
    ["c1", "c2", "c3"],
  );
});

test("작업 대화가 없으면 tasks 가 비고 모두 others 다", () => {
  const { tasks, others } = groupByTask([row("c1", "2026-10-04T09:00:00Z", null)]);
  assert.deepEqual(tasks, []);
  assert.equal(others.length, 1);
});
