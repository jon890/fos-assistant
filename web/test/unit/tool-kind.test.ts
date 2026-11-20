import assert from "node:assert/strict";
import test from "node:test";
import { activitySummaryLabel, toolKind, type ActivityUse } from "../../src/lib/tool-kind.ts";

const tool = (toolName: string, connector?: { service: string; title: string | null }): ActivityUse => ({
  kind: "TOOL",
  toolName,
  connector: connector ?? null,
});

test("검색, 페이지 읽기, Gmail 을 쓴 답은 셋을 처음 쓴 차례로 잇는다", () => {
  assert.equal(
    activitySummaryLabel(
      [tool("web_search"), tool("web_extract"), tool("mcp__gmail__get_message", { service: "Gmail", title: "메일 읽기" })],
      "done",
    ),
    "웹 검색 · 페이지 읽기 · Gmail",
  );
});

test("종류가 다섯이면 셋 뒤에 「외 2」 가 붙는다", () => {
  assert.equal(
    activitySummaryLabel(
      [tool("web_search"), tool("memory_read"), tool("terminal"), tool("vision_analyze"), { kind: "SUBAGENT" }],
      "done",
    ),
    "웹 검색 · 기억 · 파일 작업 외 2",
  );
});

test("같은 종류를 여러 번 써도 한 번만 적는다", () => {
  assert.equal(
    activitySummaryLabel(
      [tool("memory_read"), tool("mcp__fos_assistant__memory_remember"), tool("memory_search"), tool("read_file"), tool("patch")],
      "done",
    ),
    "기억 · 파일 작업",
  );
});

test("중지와 실패는 머리말 뒤에 그때까지 쓴 종류를 잇는다", () => {
  assert.equal(activitySummaryLabel([tool("web_search")], "stopped"), "하다가 멈췄어요 · 웹 검색");
  assert.equal(activitySummaryLabel([{ kind: "SUBAGENT" }], "failed"), "끝까지 하지 못했어요 · 도우미");
  assert.equal(activitySummaryLabel([], "stopped"), "하다가 멈췄어요");
  assert.equal(activitySummaryLabel([], "failed"), "끝까지 하지 못했어요");
});

test("쓴 것이 없는 블록은 「차례로 정리했어요」 다", () => {
  assert.equal(activitySummaryLabel([], "done"), "차례로 정리했어요");
});

test("플러그인을 찾지 못한 MCP 도구는 「연결된 서비스」, 그 밖은 「그 밖의 도구」 다", () => {
  assert.equal(toolKind("mcp__ledger__query").name, "연결된 서비스");
  assert.equal(toolKind("fake-tool").name, "그 밖의 도구");
  assert.equal(toolKind(null).name, "그 밖의 도구");
  assert.equal(toolKind("constructor").name, "그 밖의 도구");
});

test("지금 문장 표의 도구는 모두 종류가 있다", () => {
  const tools = [
    "terminal", "read_file", "write_file", "patch", "search_files", "skill_view", "vision_analyze", "web_search",
    "web_extract", "artifact_write", "file_space_put", "memory_read", "memory_search", "memory_remember", "memory_link",
    "follow_up_propose", "content_draft_preview", "content_draft_save", "order_limit_read", "order_limit_change",
    "approval_resume",
  ];
  for (const name of tools) assert.notEqual(toolKind(name).id, "other", name);
});

test("접힌 줄에 숫자 횟수와 내부 도구 이름이 없다", () => {
  const label = activitySummaryLabel(
    [tool("web_search"), tool("web_search"), tool("mcp__x__y"), tool("web_extract")],
    "done",
  );
  assert.doesNotMatch(label, /[0-9]|mcp__/);
});
