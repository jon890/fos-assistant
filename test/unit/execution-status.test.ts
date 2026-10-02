import assert from "node:assert/strict";
import { test } from "node:test";
import { executionStatusVariant, isInterruptedByRestart } from "../../web/src/lib/execution-status.ts";

test("도는 중인 실행은 오류 코드가 있어도 info 다", () => {
  assert.equal(executionStatusVariant({ status: "RUNNING", errorCode: null }), "info");
  assert.equal(executionStatusVariant({ status: "RUNNING", errorCode: "HERMES_BUSY" }), "info");
});

test("실패했거나 오류 코드가 있으면 destructive 다", () => {
  assert.equal(executionStatusVariant({ status: "FAILED", errorCode: null }), "destructive");
  assert.equal(executionStatusVariant({ status: "FAILED", errorCode: "HERMES_BUSY" }), "destructive");
  // 상태가 실패가 아니어도 오류 코드가 있으면 실패로 그린다.
  assert.equal(executionStatusVariant({ status: "SUCCEEDED", errorCode: "ORPHANED" }), "destructive");
  assert.equal(executionStatusVariant({ status: "CANCELLED", errorCode: "ORPHANED" }), "destructive");
});

test("취소된 실행은 outline 이고 성공한 실행은 success 다", () => {
  assert.equal(executionStatusVariant({ status: "CANCELLED", errorCode: null }), "outline");
  assert.equal(executionStatusVariant({ status: "SUCCEEDED", errorCode: null }), "success");
});

test("오류 코드를 주지 않으면 상태만으로 고른다", () => {
  assert.equal(executionStatusVariant({ status: "SUCCEEDED" }), "success");
  assert.equal(executionStatusVariant({ status: "FAILED" }), "destructive");
});

test("모르는 상태는 성공으로 그리지 않고 outline 으로 둔다", () => {
  assert.equal(executionStatusVariant({ status: "QUEUED", errorCode: null }), "outline");
  assert.equal(executionStatusVariant({ status: "", errorCode: null }), "outline");
});

test("기동 정리가 적는 오류 코드는 재시작으로 중단된 것이다", () => {
  for (const code of ["ORPHANED", "REMOTE_RUN_LOST", "RECONCILE_TIMEOUT", "RECONCILE_UNREACHABLE"]) {
    assert.equal(isInterruptedByRestart(code), true, code);
  }
  assert.equal(isInterruptedByRestart(null), false);
  assert.equal(isInterruptedByRestart(undefined), false);
  assert.equal(isInterruptedByRestart("HERMES_BUSY"), false);
});
