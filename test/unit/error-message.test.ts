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

test("꺼진 사용자에게는 계정이 중지됐다고 알리고 관리자에게 문의하게 한다", () => {
  assert.equal(describeError("ACCESS_REVOKED", "x"),
    "사용이 중지된 계정이에요. 관리자에게 문의해 주세요.");
});

test("관리 화면에는 profile과 연결 실패 원인을 구분해 알린다", () => {
  assert.match(describeAdminError("PERSON_PROFILE_TAKEN", "원본 오류"), /profile 이름/);
  assert.match(describeAdminError("HERMES_PROFILE_EXISTS", "원본 오류"), /Hermes profile/);
  assert.match(describeAdminError("HERMES_PROFILE_KEY_MISSING", "원본 오류"), /API key/);
  assert.match(describeAdminError("HERMES_UNAVAILABLE", "원본 오류"), /Hermes 런타임/);
});

test("문서를 만들고 고치다 거절되면 사용자가 할 일을 알린다", () => {
  assert.equal(describeError("MEMORY_DOCUMENT_EXISTS", "duplicate"),
    "같은 이름의 문서가 이미 있어요. 다른 이름을 입력해 주세요.");
  assert.equal(describeError("MEMORY_REVISION_CONFLICT", "conflict"),
    "그사이 문서가 바뀌었어요. 문서를 다시 열어 주세요.");
  assert.equal(describeError("MEMORY_ENCRYPTION_UNAVAILABLE", "no key"),
    "민감한 문서를 지금 저장하거나 열 수 없어요. 관리자에게 문의해 주세요.");
});

test("없는 토큰을 폐기하려 하면 새로고침을 권한다", () => {
  assert.equal(describeError("SERVICE_TOKEN_NOT_FOUND", "not found"),
    "토큰을 찾지 못했어요. 화면을 새로고침해 확인해 주세요.");
});

test("사용자 동시 실행 한도에 닿으면 끝난 뒤 다시 보내라고 알린다", () => {
  assert.equal(describeError("USER_BUSY", "fallback"),
    "진행 중인 작업이 많아요. 진행 중인 작업이 끝난 뒤 다시 보내 주세요.");
});

test("결과를 다시 전하려다 거절되면 서버 원문 대신 해요체 문구를 보인다", () => {
  assert.equal(describeError("DELIVERY_NOT_FOUND", "delivery not found"),
    "다시 전할 결과를 찾지 못했어요.");
  assert.equal(describeError("DELIVERY_NOT_RETRYABLE", "delivery is not retryable"),
    "지금은 이 결과를 다시 전할 수 없어요.");
});

test("이미 처리한 할 일을 다시 바꾸려 하면 화면을 다시 열라고 알린다", () => {
  assert.equal(describeError("FOLLOW_UP_STATE_CONFLICT", "follow-up is not in a state that allows this"),
    "이미 처리했거나 같은 할 일이 있어요. 화면을 다시 열어 주세요.");
});

test("실행 공간이 준비되지 않은 도구 저장 실패는 해요체 문구를 보인다", () => {
  assert.equal(describeError("AGENT_SANDBOX_UNAVAILABLE", "sandbox unavailable"),
    "격리된 실행 공간이 준비되지 않아 이 도구를 켤 수 없어요.");
});

test("셸이나 파일 도구가 켜진 에이전트의 주인 변경 실패는 도구를 먼저 끄라고 알린다", () => {
  assert.equal(describeError("AGENT_OWNER_CHANGE_REQUIRES_SHELL_OFF", "turn off shell first"),
    "셸이나 파일 도구가 켜진 에이전트는 주인을 바꿀 수 없어요. 먼저 그 도구를 꺼 주세요.");
});

test("비밀 요청 스킬이 있는 에이전트의 셸 도구 저장 실패는 그 스킬을 먼저 고치라고 알린다", () => {
  assert.equal(describeError("AGENT_SKILL_REQUESTS_SECRETS", "uploaded skills request secrets"),
    "이 에이전트의 스킬 가운데 환경 값이나 파일을 요청하는 것이 있어 이 도구를 켤 수 없어요. 그 스킬을 먼저 고치거나 지워 주세요.");
});
