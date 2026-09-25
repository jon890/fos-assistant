import assert from "node:assert/strict";
import test from "node:test";
import { foldVersions, isLatestView, type VersionedMessage } from "../../web/src/lib/message-versions.ts";

function message(id: number, role: VersionedMessage["role"], replacesMessageId: number | null = null) {
  return { id, role, replacesMessageId };
}

test("판이 없는 두 turn 은 각 판 수가 하나다", () => {
  const folded = foldVersions([
    message(1, "USER"), message(2, "ASSISTANT"),
    message(3, "USER"), message(4, "ASSISTANT"),
  ], {});
  assert.equal(folded.length, 2);
  assert.deepEqual(folded.map((turn) => turn.userVersion.count), [1, 1]);
  assert.deepEqual(folded.map((turn) => turn.answers[0].version.count), [1, 1]);
  assert.equal(isLatestView(folded), true);
});

test("마지막 답을 두 번 다시 생성하면 기본으로 셋째 판을 보인다", () => {
  const folded = foldVersions([
    message(1, "USER"), message(2, "ASSISTANT"),
    message(3, "ASSISTANT", 2), message(4, "ASSISTANT", 3),
  ], {});
  assert.deepEqual(folded[0].answers.map(({ message: answer }) => answer.id), [4]);
  assert.deepEqual(folded[0].answers[0].version, { slotId: 2, index: 2, count: 3 });
});

test("질문을 고치면 같은 turn 자리에서 질문과 답을 함께 넘긴다", () => {
  const messages = [
    message(1, "USER"), message(2, "ASSISTANT"),
    message(3, "USER", 1), message(4, "ASSISTANT"),
  ];
  const latest = foldVersions(messages, {});
  assert.equal(latest.length, 1);
  assert.equal(latest[0].user.id, 3);
  assert.equal(latest[0].answers[0].message.id, 4);
  assert.deepEqual(latest[0].userVersion, { slotId: 1, index: 1, count: 2 });

  const older = foldVersions(messages, { 1: 0 });
  assert.equal(older[0].user.id, 1);
  assert.equal(older[0].answers[0].message.id, 2);
  assert.equal(isLatestView(older), false);
});

test("고친 turn 에서 다시 생성한 답은 고치기 전 답과 섞이지 않는다", () => {
  const folded = foldVersions([
    message(1, "USER"), message(2, "ASSISTANT"),
    message(3, "USER", 1), message(4, "ASSISTANT"), message(5, "ASSISTANT", 4),
  ], {});
  assert.equal(folded[0].answers.length, 1);
  assert.equal(folded[0].answers[0].message.id, 5);
  assert.deepEqual(folded[0].answers[0].version, { slotId: 4, index: 1, count: 2 });
  assert.equal(foldVersions([
    message(1, "USER"), message(2, "ASSISTANT"),
    message(3, "USER", 1), message(4, "ASSISTANT"), message(5, "ASSISTANT", 4),
  ], { 1: 0 })[0].answers[0].message.id, 2);
});

test("이전 답 판을 고르면 최신 보기에서 벗어난다", () => {
  const folded = foldVersions([
    message(1, "USER"), message(2, "ASSISTANT"), message(3, "ASSISTANT", 2),
  ], { 2: 0 });
  assert.equal(folded[0].answers[0].message.id, 2);
  assert.equal(isLatestView(folded), false);
});

test("가리키는 메시지가 목록에 없어도 그 메시지를 첫 판으로 읽는다", () => {
  const folded = foldVersions([message(2, "USER", 1), message(4, "ASSISTANT", 3)], {});
  assert.equal(folded[0].userVersion.slotId, 2);
  assert.equal(folded[0].answers[0].version.slotId, 4);
});

test("답 없는 마지막 질문은 답 자리가 없고 최신 보기다", () => {
  const folded = foldVersions([message(1, "USER")], {});
  assert.deepEqual(folded[0].answers, []);
  assert.equal(isLatestView(folded), true);
});

test("답 없던 질문을 다시 시도해 생긴 답은 첫 판이다", () => {
  const folded = foldVersions([message(1, "USER"), message(2, "ASSISTANT")], {});
  assert.deepEqual(folded[0].answers[0].version, { slotId: 2, index: 0, count: 1 });
});
