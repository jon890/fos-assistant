import assert from "node:assert/strict";
import test from "node:test";
import { subagentLabel } from "../../web/src/lib/format.ts";

test("80자를 넘는 목표는 경계의 이모지를 보존하고 말줄임표까지 80자로 줄인다", () => {
  const goal = `${"가".repeat(78)}😀나다`;
  const label = subagentLabel(null, goal);
  assert.equal(label, `${"가".repeat(78)}😀…`);
  assert.equal(Array.from(label).length, 80);
});

test("80자 이하의 목표는 이모지가 있어도 줄이지 않는다", () => {
  for (const goal of ["자료를 찾는다", `${"가".repeat(79)}😀`]) {
    assert.equal(subagentLabel(null, ` ${goal} `), goal);
  }
});

test("이름이 있으면 목표보다 우선하고 앞뒤 공백을 지운다", () => {
  assert.equal(subagentLabel(" 조사 담당 ", "자료를 찾는다"), "조사 담당");
  assert.equal(subagentLabel(" ", " 자료를 찾는다 "), "자료를 찾는다");
});

test("이름과 목표가 모두 없으면 기본 문구를 쓴다", () => {
  assert.equal(subagentLabel(null, null), "하위 에이전트");
  assert.equal(subagentLabel(undefined, undefined), "하위 에이전트");
  assert.equal(subagentLabel(" ", " "), "하위 에이전트");
});
