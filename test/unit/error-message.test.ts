import assert from "node:assert/strict";
import test from "node:test";
import { describeAdminError, describeError } from "../../web/src/components/error-message.ts";

test("일반 사용자에게는 내부 원인 없이 할 일만 알린다", () => {
  assert.equal(describeError("HERMES_PROFILE_KEY_MISSING", "원본 오류"),
    "이 에이전트를 사용할 수 없어요. 관리자에게 문의해 주세요.");
  assert.equal(describeError("HERMES_UNAVAILABLE", "원본 오류"),
    "연결할 수 없어요. 잠시 뒤 다시 시도해 주세요.");
});

test("다시 생성한 스킬 커맨드가 거절되면 영어 원문 대신 해요체 문구를 보인다", () => {
  assert.equal(describeError("SKILL_COMMAND_UNKNOWN", "this agent has no enabled skill with that name"),
    "이 스킬을 이 에이전트에서 쓸 수 없어요. 스킬이 꺼졌거나 지워졌는지 확인해 주세요.");
});

test("민감 항목을 목록에서 고치려다 거절되면 여기서 고칠 수 없다고 알린다", () => {
  assert.equal(describeError("MEMORY_SENSITIVE_NOT_EDITABLE", "a sensitive memory cannot be edited from the list"),
    "민감한 항목은 여기서 고칠 수 없어요.");
});

test("관리 화면에는 profile과 연결 실패 원인을 구분해 알린다", () => {
  assert.match(describeAdminError("PERSON_PROFILE_TAKEN", "원본 오류"), /profile 이름/);
  assert.match(describeAdminError("HERMES_PROFILE_EXISTS", "원본 오류"), /Hermes profile/);
  assert.match(describeAdminError("HERMES_PROFILE_KEY_MISSING", "원본 오류"), /API key/);
  assert.match(describeAdminError("HERMES_UNAVAILABLE", "원본 오류"), /Hermes 런타임/);
});
