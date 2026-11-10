import assert from "node:assert/strict";
import { test } from "node:test";
import {
  hasBodyAfterFrontmatter,
  HERMES_SKILL_NAME_PATTERN,
  indexedDescriptionLength,
  isCountableDescription,
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

test("한 줄의 보통 설명은 화면이 글자 수를 센다", () => {
  assert.ok(isCountableDescription("주간 계획을 세운다", false));
  assert.ok(isCountableDescription("요리 🍳 it's", false), "가운데 작은따옴표 하나는 센다");
});

test("여러 줄 설명은 서버가 이어 붙이므로 화면이 세지 않는다", () => {
  assert.ok(!isCountableDescription("", true));
  assert.ok(!isCountableDescription("주간 계획", true));
});

test("YAML 이 짧게 읽는 설명은 화면이 세지 않고 서버에 맡긴다", () => {
  for (const value of ["줄\\n바꿈", "it''s", "'여는 따옴표만", "\"여는 따옴표만"]) {
    assert.ok(!isCountableDescription(value, false), value);
  }
});
