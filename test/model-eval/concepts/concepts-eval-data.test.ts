import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { CONCEPTS, PLACEHOLDERS, placeholdersOf, type Question } from "./score.ts";

const file = JSON.parse(readFileSync(new URL("questions.json", import.meta.url), "utf8")) as { version: number; questions: Question[] };
const EXPECT_KEYS = ["links", "linkPrefixes", "noLinkPrefixes", "tools", "includes", "absent", "instructionIncludes"];

test("문항마다 id, 개념, 보낼 글, 기대가 형식에 맞고 id 가 겹치지 않는다", () => {
  assert.equal(file.version, 1);
  const ids = new Set<string>();
  for (const question of file.questions) {
    assert.match(question.id, /^[a-z0-9]+(?:-[a-z0-9]+)*$/, question.id);
    assert.ok(!ids.has(question.id), `${question.id} 가 겹친다`);
    ids.add(question.id);
    assert.ok((CONCEPTS as readonly string[]).includes(question.concept), `${question.id} 의 개념`);
    assert.ok(question.messages.length > 0 && question.messages.every((text) => text.trim().length > 0), `${question.id} 의 글`);
    const keys = Object.keys(question.expect);
    assert.ok(keys.length > 0, `${question.id} 의 기대가 비었다`);
    for (const key of keys) {
      assert.ok(EXPECT_KEYS.includes(key), `${question.id} 의 모르는 기대 ${key}`);
      const list = question.expect[key as keyof Question["expect"]]!;
      assert.ok(list.length > 0 && list.every((text) => text.length > 0), `${question.id} 의 ${key}`);
    }
    for (const name of placeholdersOf(question)) assert.ok((PLACEHOLDERS as readonly string[]).includes(name), `${question.id} 의 자리 ${name}`);
  }
});

test("개념마다 문항이 둘 이상이다", () => {
  for (const concept of CONCEPTS)
    assert.ok(file.questions.filter((question) => question.concept === concept).length >= 2, `${concept} 문항`);
});

test("없어야 할 말은 완료를 말하는 꼴이라 바른 거절을 미달로 만들지 않는다", () => {
  for (const question of file.questions)
    // 과거형(했다, 었다)으로 끝내지 않은 말은 「만들었다고 할 수는 없어요」 같은 거절에도 들어 있어 완료로 끝난 꼴만 둔다. 되묻는 말은 그대로다
    for (const word of (question.expect.absent ?? []).filter((item) => /[했었웠꿨]/.test(item)))
      assert.match(word, /(?:어요|습니다)$/, `${question.id} 의 「${word}」`);
});

test("꼭 있어야 할 문항이 있다: 없는 에이전트, 없는 서비스, 연결했다고 말하지 않기", () => {
  const byConcept = (concept: string) => file.questions.filter((question) => question.concept === concept);
  assert.ok(byConcept("agent_pick").some((question) => question.expect.noLinkPrefixes?.includes("/new?agent=")), "없는 에이전트 문항");
  assert.ok(byConcept("connection").some((question) => question.expect.links?.includes("/connections") && question.expect.includes), "없는 서비스 문항");
  assert.ok(byConcept("connection").every((question) => question.expect.absent?.some((word) => word.startsWith("연결했"))), "연결했다고 말하지 않기");
  assert.equal(byConcept("model_tier").length, 0, "모델 단계는 대화로 평가하지 않는다(사용자 결정 2026-11-21)");
});
