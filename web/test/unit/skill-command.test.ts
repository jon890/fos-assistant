import assert from "node:assert/strict";
import { test } from "node:test";
import {
  commandSkillDescriptions,
  commandSkillNames,
  delegatedTargetOf,
  delegationDescriptions,
  delegationLabels,
  filterSkillNames,
  findSkillQuery,
  parseSkillCommand,
  skillDescriptionLine,
  withSkillCommand,
} from "../../src/components/chat/skill-command.ts";

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
  assert.deepEqual(commandSkillNames({ skills, editable: true, skillsToolsetEnabled: true, uploadLimit: 30 }), ["weekly-plan"]);
  assert.deepEqual(commandSkillNames({ skills, editable: true, skillsToolsetEnabled: false, uploadLimit: 30 }), []);
});

const TARGETS = [
  {
    agentCode: "career",
    agentName: "커리어",
    skills: [
      { name: "position-recommender", description: "공고를 골라요" },
      { name: "resume-review", description: "" },
    ],
  },
  { agentCode: "gmail", agentName: "Gmail 담당", skills: [{ name: "mail-digest", description: "메일을\n  요약해요" }] },
];

test("/ 목록의 설명은 비서 스킬은 이름, 맡길 스킬은 「에이전트 · 스킬」 을 키로 두고, 줄바꿈과 겹친 공백은 한 칸으로 줄인다", () => {
  const skills = [
    { name: "weekly-plan", description: "이번 주 계획을 세워요", source: "UPLOADED" as const, enabled: true },
  ];
  assert.deepEqual(commandSkillDescriptions({ skills, editable: true, skillsToolsetEnabled: true, uploadLimit: 30 }), {
    "weekly-plan": "이번 주 계획을 세워요",
  });
  assert.deepEqual(delegationDescriptions(TARGETS), {
    "커리어 · position-recommender": "공고를 골라요",
    "커리어 · resume-review": "",
    "Gmail 담당 · mail-digest": "메일을\n  요약해요",
  });
  assert.equal(skillDescriptionLine("메일을\n  요약해요 "), "메일을 요약해요");
  assert.equal(skillDescriptionLine(undefined), "");
});

test("맡길 스킬은 「에이전트 이름 · 스킬」 로 붙고, 고르면 /에이전트 이름 · 스킬 이 들어가며 이름 일부로 걸러진다(스펙 assistant-hub REQ10)", () => {
  const labels = delegationLabels(TARGETS);
  assert.deepEqual(labels, ["커리어 · position-recommender", "커리어 · resume-review", "Gmail 담당 · mail-digest"]);
  assert.deepEqual(filterSkillNames(["weekly-plan", ...labels], "커리"), ["커리어 · position-recommender", "커리어 · resume-review"]);
  assert.deepEqual(withSkillCommand("/커리", "커리어 · position-recommender"), {
    value: "/커리어 · position-recommender ",
    caret: "/커리어 · position-recommender ".length,
  });
});

test("목록에 있는 「에이전트 · 스킬」 로 시작하는 글만 맡기기이고, 비서의 /이름 과 목록에 없는 스킬은 아니다", () => {
  assert.equal(delegatedTargetOf("/커리어 · position-recommender 이번 주", TARGETS)?.agentCode, "career");
  assert.equal(delegatedTargetOf("/Gmail 담당 · mail-digest", TARGETS)?.agentCode, "gmail");
  assert.equal(delegatedTargetOf("/weekly-plan 이번 주", TARGETS), null);
  assert.equal(delegatedTargetOf("/커리어 · cooking", TARGETS), null);
  assert.equal(delegatedTargetOf("/커리어·position-recommender", TARGETS), null);
  assert.equal(delegatedTargetOf("커리어 · position-recommender", TARGETS), null);
});

