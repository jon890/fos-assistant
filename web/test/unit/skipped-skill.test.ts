import assert from "node:assert/strict";
import test from "node:test";
import {
  installedSkills,
  skippedSkillMessages,
} from "../../src/lib/skipped-skill.ts";

const NAME_TAKEN =
  "같은 이름의 스킬이 이미 있어요. 그 스킬의 이름을 바꾸거나 지우면 함께 설치돼요.";
const NO_SANDBOX =
  "이 에이전트에는 이 스킬을 돌릴 실행 환경이 없어요. 관리자에게 문의해 주세요.";
const TERMINAL_OFF = "터미널을 켜면 잠시 뒤 함께 설치돼요.";

test("이름 겹침만 있으면 이름을 바꾸거나 지우라고 안내한다", () => {
  assert.deepEqual(skippedSkillMessages(["name_taken"]), [NAME_TAKEN]);
});

test("실행 공간 미등록이나 env 없음은 관리자에게 문의하라고 안내한다", () => {
  assert.deepEqual(skippedSkillMessages(["sandbox_unregistered"]), [NO_SANDBOX]);
  assert.deepEqual(skippedSkillMessages(["sandbox_env_missing"]), [NO_SANDBOX]);
  assert.deepEqual(
    skippedSkillMessages(["sandbox_unregistered", "sandbox_env_missing"]),
    [NO_SANDBOX],
  );
});

test("터미널 꺼짐만 있으면 터미널을 켜라고 안내한다", () => {
  assert.deepEqual(skippedSkillMessages(["terminal_off"]), [TERMINAL_OFF]);
});

test("터미널 꺼짐에 다른 까닭이 함께 있으면 터미널 문구를 내지 않는다", () => {
  assert.deepEqual(
    skippedSkillMessages(["terminal_off", "sandbox_env_missing"]),
    [NO_SANDBOX],
  );
  assert.deepEqual(skippedSkillMessages(["terminal_off", "name_taken"]), [
    NAME_TAKEN,
  ]);
});

test("여러 문구가 해당하면 이름 겹침, 실행 환경 순서로 모두 보인다", () => {
  assert.deepEqual(
    skippedSkillMessages([
      "terminal_off",
      "sandbox_unregistered",
      "sandbox_env_missing",
      "name_taken",
    ]),
    [NAME_TAKEN, NO_SANDBOX],
  );
});

test("모르는 까닭과 빈 목록은 문구를 만들지 않는다", () => {
  assert.deepEqual(skippedSkillMessages(["future_reason"]), []);
  assert.deepEqual(skippedSkillMessages(["terminal_off", "future_reason"]), []);
  assert.deepEqual(skippedSkillMessages([]), []);
});

test("설치되는 스킬은 설치할 수 있는 스킬에서 빠진 스킬을 뺀 것이다", () => {
  assert.deepEqual(
    installedSkills(
      ["demo-guide", "demo-runner"],
      [{ name: "demo-runner", reasons: ["terminal_off"] }],
    ),
    ["demo-guide"],
  );
  assert.deepEqual(
    installedSkills(
      ["demo-runner"],
      [{ name: "demo-runner", reasons: ["terminal_off"] }],
    ),
    [],
  );
});
