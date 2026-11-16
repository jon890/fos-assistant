import assert from "node:assert/strict";
import test from "node:test";
import {
  agentUsesBrowser,
  browserCatalog,
  needsBrowserCatalog,
} from "../../src/lib/agent-browser.ts";

const catalog = browserCatalog([
  {
    id: "naver-shopping",
    ownerBrowser: true,
    tools: [{ name: "search_products" }, { name: "fill_checkout" }],
  },
  { id: "gmail", ownerBrowser: false, tools: [{ name: "search_messages" }] },
  { id: "old", tools: [{ name: "old_tool" }] },
]);

test("브라우저를 쓰는 플러그인 도구가 돌면 단추를 보인다", () => {
  assert.equal(
    agentUsesBrowser(["mcp__naver_shopping_3__search_products"], [], catalog),
    true,
  );
});

test("브라우저를 쓰지 않는 도구와 MCP 가 아닌 같은 이름은 단추를 보이지 않는다", () => {
  assert.equal(
    agentUsesBrowser(["mcp__gmail__search_messages"], [], catalog),
    false,
  );
  assert.equal(agentUsesBrowser(["search_products"], [], catalog), false);
  assert.equal(agentUsesBrowser([null, "mcp__old__old_tool"], [], catalog), false);
});

test("도는 도구가 없으면 보이지 않고, 브라우저 플러그인의 승인 줄이 실행 중이면 보인다", () => {
  assert.equal(agentUsesBrowser([], [], catalog), false);
  assert.equal(agentUsesBrowser([], ["naver-shopping"], catalog), true);
  assert.equal(agentUsesBrowser([], ["gmail"], catalog), false);
});

test("카탈로그가 없으면 보이지 않는다", () => {
  assert.equal(
    agentUsesBrowser(["mcp__naver_shopping__search_products"], [], null),
    false,
  );
});

test("카탈로그는 도는 MCP 도구나 실행 중인 승인 줄이 있을 때만 읽는다", () => {
  assert.equal(needsBrowserCatalog([], []), false);
  assert.equal(needsBrowserCatalog(["web_search", null], []), false);
  assert.equal(needsBrowserCatalog(["mcp__a__b"], []), true);
  assert.equal(needsBrowserCatalog([], ["gmail"]), true);
});
