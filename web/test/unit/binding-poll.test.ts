import assert from "node:assert/strict";
import test from "node:test";
import { isAwaitingApply } from "../../src/lib/binding-poll.ts";

test("붙이는 중이고 재시작 대기가 아니면 기다리면 바뀐다", () => {
  assert.equal(
    isAwaitingApply({ status: "PENDING", restartRequired: false }),
    true,
  );
});

test("재시작 대기는 관리자가 눌러야 바뀌므로 기다리지 않는다", () => {
  assert.equal(
    isAwaitingApply({ status: "PENDING", restartRequired: true }),
    false,
  );
});

test("이미 READY 이거나 붙지 않았으면 기다리지 않는다", () => {
  assert.equal(
    isAwaitingApply({ status: "READY", restartRequired: false }),
    false,
  );
  assert.equal(isAwaitingApply({ status: null, restartRequired: false }), false);
});
