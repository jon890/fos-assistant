import assert from "node:assert/strict";
import { test } from "node:test";
import {
  commandSkillNames,
  findSkillQuery,
  parseSkillCommand,
  withSkillCommand,
} from "../../web/src/components/chat/skill-command.ts";

test("이름 뒤에 공백이나 끝이 오면 스킬 커맨드이고 공백 하나를 뺀 나머지 글을 낸다", () => {
  assert.deepEqual(parseSkillCommand("/weekly-plan 이번 주"), { name: "weekly-plan", rest: "이번 주" });
  assert.deepEqual(parseSkillCommand("/weekly-plan"), { name: "weekly-plan", rest: "" });
  assert.deepEqual(parseSkillCommand("/weekly-plan\n이번 주"), { name: "weekly-plan", rest: "이번 주" });
  assert.deepEqual(parseSkillCommand("/weekly-plan\t이번 주"), { name: "weekly-plan", rest: "이번 주" });
});

test("Control Plane 의 공백 여섯 글자가 아닌 전각 공백과 NBSP 뒤의 이름은 커맨드가 아니다", () => {
  assert.equal(parseSkillCommand("/weekly-plan　이번 주"), null);
  assert.equal(parseSkillCommand("/weekly-plan 이번 주"), null);
});

test("이름 뒤에 곧바로 다른 글자가 오거나 이름이 64자를 넘으면 커맨드가 아니다", () => {
  assert.equal(parseSkillCommand("/usr/bin 은 뭐야"), null);
  assert.equal(parseSkillCommand("/Upper 대문자"), null);
  assert.deepEqual(parseSkillCommand(`/${"a".repeat(64)} 끝`), { name: "a".repeat(64), rest: "끝" });
  assert.equal(parseSkillCommand(`/${"a".repeat(65)} 끝`), null);
});

test("/ 목록은 첫 낱말 안에서만 열리고 전각 공백은 낱말을 끝내지 않는다", () => {
  assert.equal(findSkillQuery("/", 1), "");
  assert.equal(findSkillQuery("/week", 5), "week");
  assert.equal(findSkillQuery("/week 이번", 8), null);
  assert.equal(findSkillQuery("/week　", 6), "week　");
  assert.equal(findSkillQuery("week", 4), null);
  assert.equal(findSkillQuery("/week", 0), null);
});

test("고른 이름으로 첫 낱말을 바꾸고 커서를 이름 뒤 공백 다음에 둔다", () => {
  assert.deepEqual(withSkillCommand("/wee", "weekly-plan"), { value: "/weekly-plan ", caret: 13 });
  assert.deepEqual(withSkillCommand("/wee  이번 주", "weekly-plan"), { value: "/weekly-plan 이번 주", caret: 13 });
});

test("커맨드로 부를 이름은 켜진 스킬 가운데 점과 밑줄이 없는 것뿐이고 skills 도구가 꺼지면 없다", () => {
  const skills = [
    { name: "weekly-plan", description: "", source: "UPLOADED" as const, enabled: true },
    { name: "off-skill", description: "", source: "UPLOADED" as const, enabled: false },
    { name: "note_taking.v2", description: "", source: "HERMES" as const, enabled: true },
  ];
  assert.deepEqual(commandSkillNames({ skills, editable: true, skillsToolsetEnabled: true }), ["weekly-plan"]);
  assert.deepEqual(commandSkillNames({ skills, editable: true, skillsToolsetEnabled: false }), []);
});
