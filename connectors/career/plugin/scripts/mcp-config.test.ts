import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const config = JSON.parse(readFileSync(join(import.meta.dir, "../.mcp.json"), "utf8"));

test(".mcp.json 은 mcpServers 래퍼 아래에 career 서버 하나를 둔다", () => {
  expect(Object.keys(config)).toEqual(["mcpServers"]);
  expect(Object.keys(config.mcpServers)).toEqual(["career"]);
  expect(config.mcpServers.career.command).toBe("bun");
  expect(config.mcpServers.career.args).toEqual(["${CLAUDE_PLUGIN_ROOT}/dist/career-mcp.js"]);
});

test(".mcp.json env 는 변수 참조만 담고 선택값은 빈 기본값을 둔다", () => {
  expect(config.mcpServers.career.env).toEqual({
    CAREER_GITHUB_TOKEN: "${CAREER_GITHUB_TOKEN:-}",
    CAREER_GITHUB_TOKEN_URL: "${CAREER_GITHUB_TOKEN_URL}",
    CAREER_GITHUB_PROFILE_REPO: "${CAREER_GITHUB_PROFILE_REPO}",
    CAREER_BROWSER_URL: "${CAREER_BROWSER_URL}",
  });
});

// 서버는 커리어 Backend 를 부르지 않는다. Backend 주소, token, Cloudflare Access 값은 어디에도 선언하지 않는다.
test("커리어 Backend 값은 .mcp.json 에도 operator_env 에도 선언하지 않는다", () => {
  const connector = JSON.parse(readFileSync(join(import.meta.dir, "../assistant.json"), "utf8"));
  for (const name of [...Object.keys(config.mcpServers.career.env), ...connector.operator_env])
    expect(name).not.toMatch(/BACKEND/);
});
