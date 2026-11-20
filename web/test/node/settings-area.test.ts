import assert from "node:assert/strict";
import test from "node:test";
import {
  SETTINGS_GROUPS,
  isSettingsArea,
  selectedSettingsHref,
} from "../../src/components/shell/settings-area.ts";

test("설정 묶음은 다섯이고 예약 작업과 기억은 들지 않는다", () => {
  assert.deepEqual(
    SETTINGS_GROUPS.map((group) => [group.label, group.links.map((link) => link.href)]),
    [
      ["비서", ["/agents"]],
      ["알림", ["/settings/notifications"]],
      ["연결", ["/connections", "/browser"]],
      ["내 데이터", ["/files"]],
      ["도움말", ["/dev-docs", "/privacy"]],
    ],
  );
});

test("설정 화면만 설정 틀이며 실행 상세와 사용량 주소는 들지 않는다", () => {
  for (const pathname of ["/settings", "/settings/notifications", "/agents", "/agents/browser/skills/new", "/connections/x", "/browser", "/files", "/files/workspace", "/dev-docs/docs/prd.md"])
    assert.equal(isSettingsArea(pathname, true), true, pathname);
  for (const pathname of ["/usage", "/usage/executions", "/executions/12", "/", "/new", "/chat/abc", "/now", "/tasks", "/tasks/1", "/memory", "/notifications", "/admin/usage", "/agentsx"])
    assert.equal(isSettingsArea(pathname, true), false, pathname);
});

test("데이터 보관 안내는 로그인했을 때만 설정 틀이다", () => {
  assert.equal(isSettingsArea("/privacy", true), true);
  assert.equal(isSettingsArea("/privacy", false), false);
});

test("설정 첫 줄은 에이전트이며 실행 상세는 설정 줄을 고르지 않는다", () => {
  assert.equal(selectedSettingsHref("/settings"), "/agents");
  assert.equal(selectedSettingsHref("/settings/notifications"), "/settings/notifications");
  assert.equal(selectedSettingsHref("/executions/12"), null);
  assert.equal(selectedSettingsHref("/connections/abc"), "/connections");
  assert.equal(selectedSettingsHref("/privacy"), "/privacy");
  assert.equal(selectedSettingsHref("/tasks"), null);
});
