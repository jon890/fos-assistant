import assert from "node:assert/strict";
import { test } from "node:test";
import { activeAsk, todoLabel } from "../../src/lib/todo.ts";

const ASK = [
  "골라 주세요.",
  "<ask>",
  '<question header="식당">어디로 갈까?</question>',
  "<option>행복담</option>",
  "</ask>",
].join("\n");

const LATER_ASK = [
  "<ask>",
  "<question>몇 시?</question>",
  "<option>열두 시</option>",
  "</ask>",
].join("\n");

type Row = {
  id: number | string;
  role: "USER" | "ASSISTANT" | "SYSTEM";
  content: string;
  replacesMessageId?: number | null;
};

const user = (id: number, content = "물음"): Row => ({ id, role: "USER", content });
const answer = (id: number, content: string, replaces: number | null = null): Row => ({
  id,
  role: "ASSISTANT",
  content,
  replacesMessageId: replaces,
});

test("마지막 답의 마지막 묻는 카드를 고른다", () => {
  const ask = activeAsk([user(1), answer(2, `${ASK}\n\n${LATER_ASK}`)], {}, false);
  assert.equal(ask?.questions[0].text, "몇 시?");
});

test("마지막 줄이 사용자 글이거나 카드가 없는 답이면 없다", () => {
  assert.equal(activeAsk([user(1), answer(2, ASK), user(3, "행복담")], {}, false), null);
  assert.equal(activeAsk([user(1), answer(2, "그냥 답")], {}, false), null);
  assert.equal(activeAsk([], {}, false), null);
});

test("돌고 있는 turn 이 있거나 저장 전 줄이 있으면 없다", () => {
  assert.equal(activeAsk([user(1), answer(2, ASK)], {}, true), null);
  assert.equal(
    activeAsk([user(1), answer(2, ASK), { id: "user-pending", role: "USER", content: "다음" }], {}, false),
    null,
  );
});

test("이전 버전의 답을 보고 있으면 없고, 최신 버전이면 그 답의 카드다", () => {
  const turns = [user(1), answer(2, ASK), answer(3, "다시 만든 답", 2)];
  assert.equal(activeAsk(turns, {}, false), null);
  assert.equal(activeAsk(turns, { 2: 0 }, false), null);
  const regenerated = [user(1), answer(2, "처음 답"), answer(3, ASK, 2)];
  assert.equal(activeAsk(regenerated, {}, false)?.questions[0].header, "식당");
});

test("한 줄 요약은 카드가 있으면 건수를, 없으면 「할 일」 만 쓴다", () => {
  assert.equal(todoLabel(3), "할 일 3건");
  assert.equal(todoLabel(0), "할 일");
});
