import assert from "node:assert/strict";
import { test } from "node:test";
import { shellRoleState } from "../../web/src/components/shell/role-state.ts";

test("읽은 역할은 다시 읽었는지와 상관없이 그 역할이다", () => {
  assert.equal(shellRoleState("ADMIN", false), "admin");
  assert.equal(shellRoleState("ADMIN", true), "admin");
  assert.equal(shellRoleState("MEMBER", false), "member");
  assert.equal(shellRoleState("MEMBER", true), "member");
});

test("역할을 읽지 못했으면 다시 읽기 전에는 읽는 중이고 다시 읽고도 모르면 실패다", () => {
  assert.equal(shellRoleState(null, false), "reading");
  assert.equal(shellRoleState(null, true), "failed");
});

test("역할을 읽지 못한 것을 MEMBER 로 치지 않는다", () => {
  // MEMBER 로 치면 관리자의 입구가 안내 없이 사라진다.
  for (const retried of [false, true]) {
    assert.notEqual(
      shellRoleState(null, retried),
      "member",
      `retried=${retried} 일 때 null 이 member 가 됐다`,
    );
  }
});
