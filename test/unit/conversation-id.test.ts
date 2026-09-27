import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";
import { isConversationId } from "../../web/src/lib/conversation-id.ts";

test("만든 UUID 는 대화 공개 식별자다", () => {
  const id = randomUUID();
  assert.equal(isConversationId(id), true, `${id} 를 식별자로 보지 않았다`);
  assert.equal(isConversationId(id.toUpperCase()), true, `${id.toUpperCase()} 를 식별자로 보지 않았다`);
});

test("대화 번호와 빈 값은 공개 식별자가 아니다", () => {
  assert.equal(isConversationId("120"), false);
  assert.equal(isConversationId(""), false);
});

test("36자가 아닌 값은 공개 식별자가 아니다", () => {
  const id = randomUUID();
  assert.equal(isConversationId(id.slice(0, 35)), false, "한 글자 모자란 값을 식별자로 봤다");
  assert.equal(isConversationId(`${id}0`), false, "한 글자 넘치는 값을 식별자로 봤다");
});
