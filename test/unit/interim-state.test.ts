import assert from "node:assert/strict";
import test from "node:test";
import { applyChatEvent, emptyActivity, failActivity } from "../../web/src/components/chat/activity/activity-state.ts";
import { applyStreamText, type TurnStreamText } from "../../web/src/components/chat/turn-stream-text.ts";

test("다른 안내의 순서를 보존하고 공백만 다른 재수신은 한 번 표시한다", () => {
  let state = emptyActivity(0);
  for (const text of ["먼저 확인해요", "다음 안내예요", "먼저  확인해요"]) {
    state = applyChatEvent(state, { type: "interim", text });
  }
  assert.deepEqual(state.items.map((item) => item.name), ["먼저 확인해요", "다음 안내예요"]);
  assert.equal(state.endedAt, null);
  assert.equal(applyChatEvent(state, { type: "tool", toolName: "검색", phase: "started" }).items.at(-1)?.kind, "tool");
});

test("reset은 안내 중복도 비우고 done과 stop과 error는 안내를 남기지 않는다", () => {
  const event = { type: "interim" as const, text: "잠시 확인해요" };
  const state = applyChatEvent(emptyActivity(0), event);
  const reset = applyChatEvent(state, { type: "reset" });
  assert.equal(applyChatEvent(reset, event).items.length, 1);
  for (const type of ["done", "stopped"] as const) {
    const ended = applyChatEvent(state, { type });
    assert.equal(ended.items.length, 0);
    assert.equal(applyChatEvent(ended, event).items.length, 0);
  }
  assert.equal(failActivity(state, 5).items.length, 0);
});

test("완전 delta와 일부 접두사를 답에서 한 번만 빼고 안내는 전체를 표시한다", () => {
  const state: TurnStreamText = { text: "", interimKeys: new Set() };
  applyStreamText(state, { type: "delta", text: "앞 답" });
  applyStreamText(state, { type: "delta", text: "일부만 " });
  const event = { type: "interim" as const, text: "일부만 흘린 안내도 전부 보여요.", alreadyStreamed: true, streamedPrefixLength: 4 };
  assert.equal(applyStreamText(state, event), true);
  assert.equal(state.text, "앞 답");
  assert.equal(applyStreamText(state, event), false);
  assert.equal(state.text, "앞 답");
  applyStreamText(state, { type: "delta", text: "최종 답" });
  assert.equal(state.text, "앞 답최종 답");
  applyStreamText(state, { type: "reset" });
  assert.equal(state.text, "");
  assert.equal(state.interimKeys.size, 0);
});

test("미확인과 잘못된 길이는 본문을 보존한다", () => {
  for (const length of [null, undefined, -1, 999, 1.5, 0]) {
    const state: TurnStreamText = { text: "답", interimKeys: new Set() };
    assert.equal(applyStreamText(state, { type: "interim", text: "안내", alreadyStreamed: true, streamedPrefixLength: length }), false);
    assert.equal(state.text, "답");
  }
  const state: TurnStreamText = { text: "답", interimKeys: new Set() };
  assert.equal(applyStreamText(state, { type: "interim", text: "안내", streamedPrefixLength: 1 }), false);
  assert.equal(state.text, "답");
});

test("가려진 본문이 같아도 timestamp가 다른 안내는 각각 보인다", () => {
  let activity = emptyActivity(0);
  const state: TurnStreamText = { text: "", interimKeys: new Set() };
  for (const interimId of ["1.1", "1.2"]) {
    applyStreamText(state, { type: "delta", text: "가려질 안내" });
    const event = { type: "interim" as const, text: "[가림]", interimId, alreadyStreamed: true, streamedPrefixLength: 6 };
    activity = applyChatEvent(activity, event);
    assert.equal(applyStreamText(state, event), true);
    assert.equal(state.text, "");
  }
  assert.equal(activity.items.length, 2);
});

test("Unicode 공백과 emoji의 재분류 길이를 같은 실행의 답에 적용한다", () => {
  const prefix = "\u00a0\u2003확인😀\n중\u00a0";
  const state: TurnStreamText = { text: prefix, interimKeys: new Set() };
  const event = { type: "interim" as const, text: "\u00a0확인😀\u2003중이에요.\u00a0", alreadyStreamed: true, streamedPrefixLength: prefix.length };
  assert.equal(applyStreamText(state, event), true);
  assert.equal(state.text, "");
  applyStreamText(state, { type: "delta", text: "확인😀 중이에요." });
  assert.equal(applyStreamText(state, { ...event, text: "확인😀 중이에요." }), false);
  assert.equal(state.text, "확인😀 중이에요.");
});
