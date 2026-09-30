import assert from "node:assert/strict";
import test from "node:test";
import { toolLabel } from "../../web/src/lib/tool-label.ts";

test("terminal 은 도는 중에 「작업을 실행하는 중」, 끝나면 「작업 실행」 이다", () => {
  assert.equal(toolLabel("terminal", true), "작업을 실행하는 중");
  assert.equal(toolLabel("terminal", false), "작업 실행");
});

test("표에 있는 도구는 도는 줄과 끝난 줄의 말이 다르다", () => {
  assert.equal(toolLabel("vision_analyze", true), "사진을 보는 중");
  assert.equal(toolLabel("vision_analyze", false), "사진 보기");
  assert.equal(toolLabel("web_search", true), "검색하는 중");
  assert.equal(toolLabel("web_search", false), "검색");
  assert.equal(toolLabel("skill_view", true), "스킬 안내를 읽는 중");
  assert.equal(toolLabel("skill_view", false), "스킬 안내 읽기");
});

test("MCP 도구는 마지막 __ 뒤의 이름으로 표를 찾는다", () => {
  assert.equal(toolLabel("mcp__fos_assistant__artifact_write", true), "결과물을 저장하는 중");
  assert.equal(toolLabel("mcp__fos_assistant__artifact_write", false), "결과물 저장");
});

test("표에 없는 이름과 null 은 일반 문장이다", () => {
  assert.equal(toolLabel("fake-tool", true), "도구를 쓰는 중");
  assert.equal(toolLabel("fake-tool", false), "도구 사용");
  assert.equal(toolLabel(null, true), "도구를 쓰는 중");
  assert.equal(toolLabel(null, false), "도구 사용");
});

test("표에 없는 MCP 도구와 Object 의 기본 속성 이름도 일반 문장이다", () => {
  assert.equal(toolLabel("mcp__other__unknown_tool", false), "도구 사용");
  assert.equal(toolLabel("constructor", true), "도구를 쓰는 중");
});
