import assert from "node:assert/strict";
import test from "node:test";
import {
  SETTINGS_GROUPS,
  isSettingsArea,
  selectedSettingsHref,
} from "../../src/components/shell/settings-area.ts";

test("설정 묶음은 넷이고 예약 작업과 기억은 들지 않는다", () => {
  assert.deepEqual(
    SETTINGS_GROUPS.map((group) => [group.label, group.links.map((link) => link.href)]),
    [
      ["비서", ["/agents"]],
      ["연결", ["/connections", "/browser"]],
      ["내 데이터", ["/files", "/usage"]],
      ["도움말", ["/dev-docs", "/privacy"]],
    ],
  );
});

test("설정 화면들과 실행 하나의 화면은 설정 틀이고 대화 쪽 화면은 아니다", () => {
  for (const pathname of ["/settings", "/agents", "/agents/browser/skills/new", "/connections/x", "/browser", "/files", "/usage", "/dev-docs/docs/prd.md", "/executions/12"])
    assert.equal(isSettingsArea(pathname, true), true, pathname);
  for (const pathname of ["/", "/new", "/chat/abc", "/now", "/tasks", "/tasks/1", "/memory", "/notifications", "/admin/usage", "/agentsx"])
    assert.equal(isSettingsArea(pathname, true), false, pathname);
});

test("데이터 보관 안내는 로그인했을 때만 설정 틀이다", () => {
  assert.equal(isSettingsArea("/privacy", true), true);
  assert.equal(isSettingsArea("/privacy", false), false);
});

test("골라진 줄은 /settings 에서 에이전트, 실행 하나에서 사용량이다", () => {
  assert.equal(selectedSettingsHref("/settings"), "/agents");
  assert.equal(selectedSettingsHref("/executions/12"), "/usage");
  assert.equal(selectedSettingsHref("/connections/abc"), "/connections");
  assert.equal(selectedSettingsHref("/privacy"), "/privacy");
  assert.equal(selectedSettingsHref("/tasks"), null);
});
