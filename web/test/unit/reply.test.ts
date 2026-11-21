import assert from "node:assert/strict";
import test from "node:test";
import { excerptOf, replyRequestOf, replyTargetOf, versionOf } from "../../src/lib/reply.ts";

const answer = {
  id: 7,
  role: "ASSISTANT" as const,
  content: "\n첫 줄\n\n둘째 줄\n셋째 줄",
  artifacts: [
    { path: "generated/고양이.png", deleted: false, kind: "IMAGE" },
    { path: "generated/강아지.png", deleted: true, kind: "IMAGE" },
    { path: "page.html", deleted: false, kind: "HTML" },
  ],
};

test("상자 글은 빈 줄을 뺀 앞 두 줄이고 줄마다 200자에서 자른다", () => {
  assert.equal(excerptOf(answer.content), "첫 줄\n둘째 줄");
  assert.equal(excerptOf("가".repeat(201)), `${"가".repeat(200)}…`);
});

test("글을 집으면 메시지 전체와 그 안의 그림을, 그림을 집으면 그 한 장만 담는다", () => {
  const whole = replyTargetOf(answer);
  assert.deepEqual(whole?.images.map((image) => [image.path, image.deleted]), [
    ["generated/고양이.png", false],
    ["generated/강아지.png", true],
  ]);
  assert.deepEqual(replyRequestOf(whole!), { messageId: 7, attachmentId: null, imagePath: null });

  const one = replyTargetOf(answer, { imagePath: "generated/고양이.png" });
  assert.deepEqual(one?.images.map((image) => image.path), ["generated/고양이.png"]);
  assert.deepEqual(replyRequestOf(one!), { messageId: 7, attachmentId: null, imagePath: "generated/고양이.png" });
});

test("사진 한 장을 집으면 그 사진만 담는다", () => {
  const mine = {
    id: 3,
    role: "USER" as const,
    content: "신발",
    attachments: [{ id: 10, visible: true }, { id: 11, visible: false }],
  };
  const picked = replyTargetOf(mine, { attachmentId: 11 });
  assert.deepEqual(picked?.images, [{ kind: "ATTACHMENT", path: null, attachmentId: 11, deleted: true }]);
});

test("저장되지 않은 줄, 알림 줄, 끼워 넣은 글은 집지 않는다", () => {
  assert.equal(replyTargetOf({ ...answer, id: "assistant-1" }), null);
  assert.equal(replyTargetOf({ id: 1, role: "SYSTEM", content: "알림" }), null);
  assert.equal(replyTargetOf({ id: 2, role: "USER", content: "끼움", steer: { state: "DELIVERED" } }), null);
});

test("다시 만든 답의 이전 버전이면 묶음과 자리를 낸다", () => {
  const messages = [
    { id: 1, replacesMessageId: null },
    { id: 2, replacesMessageId: null },
    { id: 5, replacesMessageId: 2 },
    { id: 9, replacesMessageId: 5 },
  ];
  assert.deepEqual(versionOf(messages, 5), { slotId: 2, index: 1 });
  assert.deepEqual(versionOf(messages, 2), { slotId: 2, index: 0 });
  assert.equal(versionOf(messages, 1), null);
});
