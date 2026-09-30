import assert from "node:assert/strict";
import { test } from "node:test";
import {
  hasBodyAfterFrontmatter,
  HERMES_SKILL_NAME_PATTERN,
  indexedDescriptionLength,
  SKILL_NAME_PATTERN,
} from "../../web/src/lib/skill.ts";

test("Hermes 이름 규칙은 점과 밑줄이 든 이름을 받는다", () => {
  for (const name of ["hermes-help", "note_taking.v2", "a", "a".repeat(64)]) {
    assert.ok(HERMES_SKILL_NAME_PATTERN.test(name), name);
  }
});

test("Hermes 이름 규칙은 점으로 시작하거나 대문자, 경로 구분자, 65자를 받지 않는다", () => {
  for (const name of ["", ".", "..", ".hidden", "Upper", "a/b", "a".repeat(65)]) {
    assert.ok(!HERMES_SKILL_NAME_PATTERN.test(name), name);
  }
});

test("올린 스킬 이름 규칙은 점과 밑줄을 받지 않는다", () => {
  assert.ok(SKILL_NAME_PATTERN.test("weekly-plan"));
  assert.ok(!SKILL_NAME_PATTERN.test("note_taking.v2"));
});

test("설명 글자 수는 앞뒤 공백과 양 끝 따옴표를 빼고 code point 로 센다", () => {
  assert.equal(indexedDescriptionLength("가".repeat(60)), 60);
  assert.equal(indexedDescriptionLength(`  "'${"가".repeat(10)}'"  `), 10);
  assert.equal(indexedDescriptionLength("😀"), 1);
});

test("앞머리 뒤에 공백뿐이면 본문이 없다", () => {
  assert.ok(hasBodyAfterFrontmatter("\n# 본문\n"));
  assert.ok(!hasBodyAfterFrontmatter(""));
  assert.ok(!hasBodyAfterFrontmatter("\n  \n"));
});
