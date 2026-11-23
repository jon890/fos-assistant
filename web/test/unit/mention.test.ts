import assert from "node:assert/strict";
import test from "node:test";
import { pendingAnswerLabel, type MentionDelegation } from "../../src/lib/mention.ts";

/** 지난 `@` 맡김 질문이다. `@` 로 새로 맡기는 길은 없앴지만 그 전에 남은 줄은 그린다(스펙 assistant-hub REQ6) */
const mention: MentionDelegation = {
  executionId: 7,
  parentExecutionId: null,
  status: "SUCCEEDED",
  agent: { code: "shopper", name: "쇼핑 담당", icon: "🛍️", color: null },
};

test("아직 저장되지 않은 답은 마지막 질문이 맡김일 때만 맡은 에이전트로 그린다", () => {
  assert.equal(
    pendingAnswerLabel([
      { role: "USER", delegatedTo: mention },
      { role: "ASSISTANT" },
    ])?.name,
    "쇼핑 담당",
  );
  assert.equal(
    pendingAnswerLabel([
      { role: "USER", delegatedTo: mention },
      { role: "ASSISTANT" },
      { role: "USER" },
    ]),
    null,
  );
  assert.equal(
    pendingAnswerLabel([
      { role: "USER", delegatedTo: mention },
      { role: "ASSISTANT" },
      { role: "SYSTEM" },
      { role: "ASSISTANT" },
    ]),
    null,
    "알림 줄 뒤의 자동 turn 은 대화의 에이전트로 그린다",
  );
  assert.equal(pendingAnswerLabel([]), null);
});
