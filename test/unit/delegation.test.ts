import assert from "node:assert/strict";
import test from "node:test";
import {
  delegationStatusLabel,
  labelIdentity,
  mergeDelegations,
  receiveDelegation,
  requestedByText,
  resultArrivalText,
  type DelegationLine,
} from "../../web/src/lib/delegation.ts";

const SHOPPER = {
  code: "shopper",
  name: "쇼핑 담당",
  icon: "🛍️",
  color: "ROSE",
};
const DELETED = { code: null, name: null, icon: null, color: null };

function line(
  executionId: number,
  status: DelegationLine["status"],
  parentExecutionId = 10,
): DelegationLine {
  return { executionId, parentExecutionId, status, agent: SHOPPER };
}

test("맡긴 줄의 상태는 진행 중, 끝남, 실패, 멈춤으로 보인다", () => {
  assert.deepEqual(
    (["RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"] as const).map(
      delegationStatusLabel,
    ),
    ["진행 중", "끝남", "실패", "멈춤"],
  );
});

test("지운 에이전트의 이름표는 「지운 에이전트」 와 코드 없는 회색이다", () => {
  assert.deepEqual(labelIdentity(DELETED), {
    name: "지운 에이전트",
    code: null,
  });
  assert.equal(labelIdentity(SHOPPER).name, "쇼핑 담당");
});

test("결과 도착 줄은 받침에 맞는 조사로 끝냈는지 못했는지 알린다", () => {
  assert.equal(resultArrivalText(line(1, "SUCCEEDED")), "쇼핑 담당이 끝냈어요");
  assert.equal(
    resultArrivalText({
      ...line(1, "FAILED"),
      agent: { ...SHOPPER, name: "비서" },
    }),
    "비서가 하지 못했어요",
  );
  assert.equal(requestedByText(SHOPPER), "쇼핑 담당이 요청");
});

test("사건으로 받은 줄은 이력의 진행 중 줄을 끝난 상태로 바꾸고 다른 답의 줄은 섞지 않는다", () => {
  const merged = mergeDelegations(
    [line(2, "RUNNING")],
    [line(2, "SUCCEEDED"), line(1, "RUNNING"), line(3, "RUNNING", 99)],
    10,
  );
  assert.deepEqual(
    merged.map((it) => [it.executionId, it.status]),
    [
      [1, "RUNNING"],
      [2, "SUCCEEDED"],
    ],
  );
});

test("늦게 온 진행 중 줄이 이력의 끝난 줄을 덮지 않는다", () => {
  const merged = mergeDelegations(
    [line(2, "FAILED")],
    [line(2, "RUNNING")],
    10,
  );
  assert.equal(merged[0].status, "FAILED");
});

test("실행 번호를 모르는 답은 이력의 줄만 그린다", () => {
  assert.deepEqual(mergeDelegations(undefined, [line(1, "RUNNING")], null), []);
});

test("사건을 받을 때 끝난 줄은 다시 진행 중으로 돌아가지 않는다", () => {
  const finished = receiveDelegation([], line(1, "SUCCEEDED"));
  assert.equal(receiveDelegation(finished, line(1, "RUNNING")), finished);
  const started = receiveDelegation([], line(2, "RUNNING"));
  assert.deepEqual(receiveDelegation(started, line(2, "CANCELLED")), [
    line(2, "CANCELLED"),
  ]);
});
