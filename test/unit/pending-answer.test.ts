import assert from "node:assert/strict";
import { test } from "node:test";
import { createPendingAnswer } from "../../web/src/components/chat/pending-answer.ts";
import type { Turn } from "../../web/src/components/chat/message-types.ts";

// 브라우저 밖에서는 화면 새로 고침 호출이 없으므로 타이머로 대신한다.
const globals = globalThis as unknown as Record<string, unknown>;
globals.requestAnimationFrame = (callback: () => void) => setTimeout(callback, 16);
globals.cancelAnimationFrame = (id: ReturnType<typeof setTimeout>) => clearTimeout(id);

/** React 의 상태 갱신 함수처럼 값이나 갱신 함수를 받는 저장소다. */
function store(initial: Turn[]) {
  let turns = initial;
  return {
    get: () => turns,
    set: (change: Turn[] | ((previous: Turn[]) => Turn[])) => {
      turns = typeof change === "function" ? change(turns) : change;
    },
  };
}

test("조각이 오면 임시 답 turn 에 받은 글을 쌓고 보이는 글은 reveal 이 맡는다", () => {
  const turns = store([{ id: 1, role: "USER", content: "질문", senderName: null }]);
  const answer = createPendingAnswer(turns.set, "assistant-1");
  answer.onDelta?.("안녕하세요 ");
  answer.onDelta?.("반갑습니다 ");
  const pending = turns.get().find((turn) => turn.id === "assistant-1");
  assert.equal(pending?.content, "안녕하세요 반갑습니다 ");
  assert.equal(pending?.reveal?.getSnapshot().text, "");
  assert.equal(turns.get().length, 2);
});

test("reset 은 임시 답 turn 을 지우고 다음 조각이 새로 만든다", () => {
  const turns = store([]);
  const answer = createPendingAnswer(turns.set, "assistant-1");
  answer.onDelta?.("막힌 시도 ");
  answer.onReset?.();
  assert.deepEqual(turns.get(), []);
  answer.onDelta?.("새 답 ");
  assert.equal(turns.get()[0].content, "새 답 ");
});

test("받은 글이 줄면 같은 turn 의 받은 글만 바꾸고, 빈 글이면 turn 을 지운다", () => {
  const turns = store([]);
  const answer = createPendingAnswer(turns.set, "assistant-1");
  answer.onDelta?.("앞 답 일부만 ");
  const reveal = turns.get()[0].reveal;
  answer.onReplace?.("앞 답 ");
  assert.equal(turns.get()[0].content, "앞 답 ");
  assert.equal(turns.get()[0].reveal, reveal);
  answer.onReplace?.("");
  assert.deepEqual(turns.get(), []);
});

test("finish 는 남은 글이 모두 보이면 이행한다", async () => {
  const turns = store([]);
  const answer = createPendingAnswer(turns.set, "assistant-1");
  answer.onDelta?.("끝나기 전에 아직 다 보이지 않은 긴 답이에요 ".repeat(5));
  await answer.finish();
  assert.equal(
    turns.get()[0].reveal?.getSnapshot().text,
    turns.get()[0].content,
  );
});
