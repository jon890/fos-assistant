import assert from "node:assert/strict";
import test from "node:test";
import { agentLabel } from "../../web/src/lib/format.ts";

test("이름이 있으면 그 이름을 그대로 보인다", () => {
  assert.equal(agentLabel("가족 비서"), "가족 비서");
});

test("이름이 없으면 지운 에이전트로 보인다", () => {
  assert.equal(agentLabel(null), "지운 에이전트");
});
