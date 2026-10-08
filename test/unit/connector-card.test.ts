import assert from "node:assert/strict";
import test from "node:test";
import {
  type ConnectorTool,
  connectorIconSrc,
  connectorLinkHref,
  toolRiskCounts,
} from "../../web/src/lib/connection.ts";

const SVG =
  "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciLz4=";
const PNG =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==";

test("아이콘은 svg 와 png 의 base64 data URL 만 받는다", () => {
  assert.equal(connectorIconSrc(SVG), SVG);
  assert.equal(connectorIconSrc(PNG), PNG);
});

test("아이콘의 모양이 틀리면 null 이다", () => {
  for (const bad of [
    "data:text/html;base64,PHNjcmlwdD4=",
    "javascript:alert(1)",
    "https://example.com/icon.png",
    "data:image/png;base64,abc def",
    "data:image/png;base64,",
    "",
    null,
    undefined,
  ]) {
    assert.equal(
      connectorIconSrc(bad),
      null,
      `받으면 안 되는 값: ${String(bad)}`,
    );
  }
});

test("링크는 로그인 정보가 없는 https 주소만 받는다", () => {
  assert.equal(
    connectorLinkHref("https://example.com/a"),
    "https://example.com/a",
  );
});

test("https 가 아니거나 깨진 링크는 null 이다", () => {
  for (const bad of [
    "http://example.com/a",
    "javascript:alert(1)",
    "https://user:pw@example.com/",
    "https://user@example.com/",
    "not a url",
    "",
    null,
    undefined,
  ]) {
    assert.equal(
      connectorLinkHref(bad),
      null,
      `받으면 안 되는 값: ${String(bad)}`,
    );
  }
});

function tool(name: string, risk: ConnectorTool["risk"]): ConnectorTool {
  return { name, title: null, risk, approval: "REQUIRED", grant: true };
}

test("도구 요약은 위험도 순서를 지키고 0개인 위험도를 뺀다", () => {
  const counts = toolRiskCounts([
    tool("a", "DESTRUCTIVE"),
    tool("b", "WRITE"),
    tool("c", "READ"),
    tool("d", "WRITE"),
  ]);
  assert.deepEqual(counts, [
    { risk: "READ", label: "조회", count: 1 },
    { risk: "WRITE", label: "쓰기", count: 2 },
    { risk: "DESTRUCTIVE", label: "되돌리기 어려운 쓰기", count: 1 },
  ]);
});

test("도구가 없으면 요약도 비어 있다", () => {
  assert.deepEqual(toolRiskCounts([]), []);
});
