import assert from "node:assert/strict";
import test from "node:test";
import {
  queueSourceOf,
  visiblePendingItems,
  type PendingMessage,
} from "../../src/lib/pending-messages.ts";

const PLAIN: PendingMessage = { id: 1, text: "보통 글", createdAt: "" };
const SHOWN: PendingMessage = {
  id: 2,
  text: "이미 말풍선인 글",
  createdAt: "",
  messageId: 40,
};

test("멈추지 않은 줄은 말풍선 번호가 붙은 글을 그리지 않는다", () => {
  assert.deepEqual(
    visiblePendingItems({ held: false, items: [SHOWN, PLAIN] }),
    [PLAIN],
  );
});

test("멈춘 줄은 말풍선 번호가 붙은 글도 그려 사용자가 고르게 한다", () => {
  assert.deepEqual(
    visiblePendingItems({ held: true, items: [SHOWN, PLAIN] }),
    [SHOWN, PLAIN],
  );
});

test("옛 서버 응답처럼 칸이 없으면 보통 글로 본다", () => {
  assert.deepEqual(visiblePendingItems({ held: false, items: [PLAIN] }), [
    PLAIN,
  ]);
});

test("글을 따로 주지 않은 보내기만 입력창 글로 끼워 넣기를 청한다", () => {
  assert.equal(queueSourceOf(undefined), "composer");
  assert.equal(queueSourceOf("카드 답"), "other");
  assert.equal(queueSourceOf(""), "other");
});
