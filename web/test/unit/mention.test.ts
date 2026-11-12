import assert from "node:assert/strict";
import test from "node:test";
import {
  mentionTargets,
  pendingAnswerLabel,
  pendingMention,
} from "../../src/lib/mention.ts";
import type { AgentView } from "../../src/lib/agent.ts";

function agent(code: string, name: string): AgentView {
  return {
    code,
    name,
    visibility: "PRIVATE",
    acceptsAttachments: true,
    editable: true,
    ownedByMe: true,
    icon: code === "shopper" ? "🛍️" : null,
    color: null,
  };
}

const CHIEF = agent("chief", "비서");
const SHOPPER = agent("shopper", "쇼핑 담당");
const TRAIN = agent("train", "기차 담당");

test("`@` 목록은 대화의 에이전트를 빼고 순서를 지킨다", () => {
  assert.deepEqual(
    mentionTargets([CHIEF, SHOPPER, TRAIN], "chief").map((it) => it.code),
    ["shopper", "train"],
  );
  assert.deepEqual(mentionTargets([CHIEF], "chief"), []);
});

test("보내는 중인 맡김 질문은 실행 번호 없이 진행 중이고 고른 에이전트의 이름표다", () => {
  assert.deepEqual(pendingMention(SHOPPER), {
    executionId: null,
    parentExecutionId: null,
    status: "RUNNING",
    agent: { code: "shopper", name: "쇼핑 담당", icon: "🛍️", color: null },
  });
});

test("아직 저장되지 않은 답은 마지막 질문이 맡김일 때만 맡은 에이전트로 그린다", () => {
  const mention = pendingMention(SHOPPER);
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
  assert.equal(pendingAnswerLabel([]), null);
});
