// 평가 묶음 파일로 옮긴 서비스 개념 문항이 옛 평가 세트와 같은지 본다(docs/features/agent-quality/design.md 의 「묶음 파일」, 스펙 REQ6).
// 옛 평가 세트(test/model-eval/concepts/)를 지우는 PR 이 이 테스트도 함께 지운다.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const REPO_ROOT = join(import.meta.dirname, "../../");

type OldQuestion = { id: string; concept: string; messages: string[]; expect: Record<string, string[]> };
type Turn = { text: string; card?: string; attachment?: string };
type NewQuestion = { id: string; tag: string; turns: Turn[]; expect: Record<string, unknown> };

function readJson<T>(relative: string): T {
  return JSON.parse(readFileSync(join(REPO_ROOT, relative), "utf8")) as T;
}

test("서비스 개념 묶음은 옛 평가 세트의 문항 id, 글, 기대를 그대로 옮긴다", () => {
  const old = readJson<{ questions: OldQuestion[] }>("test/model-eval/concepts/questions.json").questions;
  const bundle = readJson<{ id: string; questions: NewQuestion[] }>("backend/src/main/resources/quality/bundles/service-concepts.json");

  assert.equal(bundle.id, "service-concepts");
  assert.deepEqual(bundle.questions.map((question) => question.id), old.map((question) => question.id));
  for (const [index, question] of bundle.questions.entries()) {
    const before = old[index];
    assert.equal(question.tag, before.concept, question.id);
    assert.deepEqual(question.turns.map((turn) => turn.text), before.messages, question.id);
    assert.ok(question.turns.every((turn) => turn.card === undefined && turn.attachment === undefined), question.id);
    assert.deepEqual(question.expect, before.expect, question.id);
  }
});
