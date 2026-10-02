import assert from "node:assert/strict";
import test from "node:test";
import { isAccessRevoked } from "../../web/src/lib/access-revoked.ts";

test("401 이고 code 가 ACCESS_REVOKED 면 꺼진 사용자다", () => {
  assert.equal(isAccessRevoked(401, JSON.stringify({ code: "ACCESS_REVOKED", message: "x" })), true);
});

test("401 이어도 code 가 다르면 꺼진 사용자가 아니다", () => {
  assert.equal(isAccessRevoked(401, JSON.stringify({ code: "UNAUTHENTICATED", message: "x" })), false);
});

test("code 가 ACCESS_REVOKED 여도 401 이 아니면 꺼진 사용자가 아니다", () => {
  assert.equal(isAccessRevoked(403, JSON.stringify({ code: "ACCESS_REVOKED" })), false);
});

test("401 의 본문이 JSON 이 아니면 꺼진 사용자가 아니다", () => {
  assert.equal(isAccessRevoked(401, "<html>"), false);
});

test("401 의 본문이 비어 있으면 꺼진 사용자가 아니다", () => {
  assert.equal(isAccessRevoked(401, ""), false);
});
