import assert from "node:assert/strict";
import test from "node:test";
import { isReadableDetail, toolLabel } from "../../web/src/lib/tool-label.ts";

test("terminal 은 도는 중에 「작업하고 있어요」, 끝나면 「작업을 했어요」 다", () => {
  assert.equal(toolLabel("terminal", true), "작업하고 있어요");
  assert.equal(toolLabel("terminal", false), "작업을 했어요");
});

test("표에 있는 도구는 도는 줄과 끝난 줄의 말이 다르다", () => {
  assert.equal(toolLabel("vision_analyze", true), "사진을 보고 있어요");
  assert.equal(toolLabel("vision_analyze", false), "사진을 봤어요");
  assert.equal(toolLabel("web_search", true), "검색하고 있어요");
  assert.equal(toolLabel("web_search", false), "검색했어요");
  assert.equal(toolLabel("skill_view", true), "스킬 안내를 읽고 있어요");
  assert.equal(toolLabel("skill_view", false), "스킬 안내를 읽었어요");
  assert.equal(toolLabel("memory_read", true), "기억을 떠올리고 있어요");
  assert.equal(toolLabel("memory_read", false), "기억을 떠올렸어요");
});

test("MCP 도구는 마지막 __ 뒤의 이름으로 표를 찾는다", () => {
  assert.equal(toolLabel("mcp__fos_assistant__artifact_write", true), "결과물을 저장하고 있어요");
  assert.equal(toolLabel("mcp__fos_assistant__artifact_write", false), "결과물을 저장했어요");
  assert.equal(toolLabel("mcp__fos__artifact_write", false), "결과물을 저장했어요");
  assert.equal(toolLabel("mcp__fos_assistant__follow_up_propose", true), "할 일을 제안하고 있어요");
  assert.equal(toolLabel("mcp__fos_assistant__follow_up_propose", false), "할 일을 제안했어요");
});

test("표에 없는 이름과 null 은 일반 문장이다", () => {
  assert.equal(toolLabel("fake-tool", true), "도구를 쓰고 있어요");
  assert.equal(toolLabel("fake-tool", false), "도구를 썼어요");
  assert.equal(toolLabel(null, true), "도구를 쓰고 있어요");
  assert.equal(toolLabel(null, false), "도구를 썼어요");
});

test("표에 없는 MCP 도구는 이름 원문 대신 연결된 서비스를 썼다고 보인다", () => {
  assert.equal(toolLabel("mcp__ledger__query", true), "연결된 서비스를 쓰고 있어요");
  assert.equal(toolLabel("mcp__ledger__query", false), "연결된 서비스를 썼어요");
  assert.equal(toolLabel("mcp__other__unknown_tool", false), "연결된 서비스를 썼어요");
});

test("Object 의 기본 속성 이름은 일반 문장이다", () => {
  assert.equal(toolLabel("constructor", true), "도구를 쓰고 있어요");
  assert.equal(toolLabel("mcp__other__constructor", false), "연결된 서비스를 썼어요");
});

test("검색어와 사진 질문만 줄에 바로 보여도 되는 detail 이다", () => {
  assert.equal(isReadableDetail("web_search"), true);
  assert.equal(isReadableDetail("vision_analyze"), true);
  assert.equal(isReadableDetail("mcp__search__web_search"), true);
  assert.equal(isReadableDetail("terminal"), false);
  assert.equal(isReadableDetail("mcp__ledger__query"), false);
  assert.equal(isReadableDetail(null), false);
});
