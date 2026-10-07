import assert from "node:assert/strict";
import test from "node:test";
import {
  memorySourceLabel,
  withSubjectParticle,
} from "../../web/src/lib/memory-source.ts";

test("받침에 맞춰 주격 조사를 붙인다", () => {
  assert.equal(withSubjectParticle("집안일 도우미"), "집안일 도우미가");
  assert.equal(withSubjectParticle("가족 비서"), "가족 비서가");
  assert.equal(withSubjectParticle("일정 관리인"), "일정 관리인이");
  assert.equal(withSubjectParticle("Bot"), "Bot이");
  assert.equal(withSubjectParticle("Alice"), "Alice가");
  assert.equal(withSubjectParticle("비서 2"), "비서 2가");
  assert.equal(withSubjectParticle("비서 3"), "비서 3이");
});

test("사람이 만든 기억은 직접 남겼다고 보인다", () => {
  assert.equal(memorySourceLabel({ proposedByExecutionId: null }), "직접 남김");
});

test("볼 수 있는 에이전트는 이름을, 모르는 에이전트는 이름 없이 보인다", () => {
  assert.equal(
    memorySourceLabel({
      proposedByExecutionId: 3,
      sourceAgentName: "가족 비서",
    }),
    "가족 비서가 남김",
  );
  assert.equal(
    memorySourceLabel({ proposedByExecutionId: 3, sourceAgentName: null }),
    "에이전트가 남김",
  );
});

test("지운 에이전트는 정해진 문구로 보인다", () => {
  assert.equal(
    memorySourceLabel({ proposedByExecutionId: 3, sourceAgentDeleted: true }),
    "지운 에이전트가 남김",
  );
});
