import { expect, test } from "bun:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { createServer } from "../src/server.ts";
import { toolDefinitions } from "../src/tools.ts";

const read = (name: string) => JSON.parse(readFileSync(join(import.meta.dir, "..", name), "utf8"));
const manifest = read("assistant.json");
const mcp = read(".mcp.json");

type ToolPolicy = { risk: string; approval: string; title?: string };
const policies = Object.entries(manifest.tools as Record<string, ToolPolicy>);

test("schema 2 정책은 실제 MCP 도구를 빠짐없이 선언하고 미선언 도구를 거절한다", () => {
  expect(manifest.schema).toBe(2);
  expect(manifest.default_tool_policy).toBe("deny");
  expect(Object.keys(manifest.tools).sort()).toEqual(Object.keys(toolDefinitions).sort());
});

test("도구는 GitHub, 원티드, 연결 확인뿐이고 커리어 Backend 의 token 칸과 주소가 선언에 없다", () => {
  expect(Object.keys(manifest.tools).sort()).toEqual([
    "check_connection",
    "get_github_profile",
    "get_wanted_resume",
    "update_github_profile",
    "update_wanted_resume",
  ]);
  expect(manifest.fields.map((field: { key: string }) => field.key)).toEqual(["github_token"]);
  // 필수 칸이 없다. GitHub 을 연결하지 않아도 플러그인을 붙일 수 있다.
  expect(manifest.fields.filter((field: { required?: boolean }) => field.required !== false)).toEqual([]);
  expect(manifest.operator_env).toEqual(["CAREER_GITHUB_PROFILE_REPO"]);
  for (const file of ["assistant.json", ".mcp.json", ".claude-plugin/plugin.json"])
    expect({ file, hit: /CAREER_BACKEND|Backend/.test(readFileSync(join(import.meta.dir, "..", file), "utf8")) }).toEqual({
      file,
      hit: false,
    });
});

test("확인 도구는 승인 없는 READ 정책이고 서버가 readOnlyHint 로 표시하며 GitHub 연결 없이도 성공한다", async () => {
  expect(manifest.verify.tool).toBe("check_connection");
  expect(manifest.tools[manifest.verify.tool]).toMatchObject({ risk: "READ", approval: "none" });
  const server = createServer({ CAREER_GITHUB_PROFILE_REPO: "example-user/example-user" });
  const client = new Client({ name: "assistant-config-test", version: "1.0.0" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    const { tools } = await client.listTools();
    const verifyTool = tools.find((tool) => tool.name === manifest.verify.tool);
    expect(verifyTool?.annotations?.readOnlyHint).toBe(true);
    const result = await client.callTool({ name: manifest.verify.tool, arguments: {} });
    expect(result.isError).not.toBe(true);
    expect(result.structuredContent).toEqual({ github: "not_configured" });
  } finally {
    await client.close();
    await server.close();
  }
});

test("조회 도구는 READ 와 none, 저장과 갱신 도구는 WRITE 와 required 다", () => {
  for (const [name, policy] of policies) {
    expect({ name, known: /^(check|get|update)_/.test(name) }).toEqual({ name, known: true });
    if (/^(check|get)_/.test(name))
      expect({ name, ...policy }).toMatchObject({ name, risk: "READ", approval: "none" });
    if (/^update_/.test(name))
      expect({ name, ...policy }).toMatchObject({ name, risk: "WRITE", approval: "required" });
  }
});

test("모든 도구에 승인 카드에 보일 80자 이하의 title 이 있다", () => {
  for (const [name, policy] of policies) {
    expect({ name, hasTitle: typeof policy.title === "string" && policy.title.length > 0 }).toEqual({
      name,
      hasTitle: true,
    });
    expect(policy.title!.length).toBeLessThanOrEqual(80);
  }
});

// errors 항목은 공통 어휘 하나이거나 recovery 를 함께 가진 {category, ...} 다.
const errorCategories = Object.fromEntries(
  Object.entries(manifest.errors as Record<string, string | { category: string }>).map(([code, entry]) => [
    code,
    typeof entry === "string" ? entry : entry.category,
  ]),
);

test(".mcp.json 서버 env 는 fields[].env, operator_env, owner_browser_env, oauth.token_env 의 합과 같다", () => {
  const expected = [
    ...manifest.fields.map((f: { env: string }) => f.env),
    ...manifest.operator_env,
    manifest.owner_browser_env,
    manifest.oauth.token_env,
  ].sort();
  const server = mcp.mcpServers.career.env;
  expect(Object.keys(server).sort()).toEqual(expected);
  for (const [name, value] of Object.entries(server))
    expect(String(value)).toMatch(new RegExp(`^\\$\\{${name}(:-)?\\}$`));
});

test("errors 표의 값은 공통 어휘만 쓴다", () => {
  const vocabulary = ["credential_rejected", "forbidden", "invalid_input", "outcome_unknown", "unavailable"];
  for (const value of Object.values(errorCategories)) expect(vocabulary).toContain(value);
});

test("결과를 알 수 없는 저장 도구가 없어 재시도 식별자를 선언하지 않는다", () => {
  expect(Object.values(errorCategories)).not.toContain("outcome_unknown");
});

test("운영자 비밀값과 사진 도구 묶음을 선언하지 않는다", () => {
  for (const key of ["operator_secrets", "toolsets", "attachments"])
    expect(Object.hasOwn(manifest, key)).toBe(false);
});

test("plugin.json 과 package.json 의 version 이 같다", () => {
  expect(read(".claude-plugin/plugin.json").version).toBe(read("package.json").version);
});

test("WRITE 는 GitHub·원티드 갱신 도구뿐이며 모두 승인이 필요하다", () => {
  const writes = policies.filter(([, policy]) => policy.risk === "WRITE");
  expect(writes.map(([name]) => name).sort()).toEqual(["update_github_profile", "update_wanted_resume"]);
  for (const [name, policy] of writes) expect({ name, approval: policy.approval }).toEqual({ name, approval: "required" });
});

test("errors 는 docs/features/connector.md 의 커리어 오류 코드 표에서 공통 어휘가 있는 줄과 같다", () => {
  const doc = readFileSync(join(import.meta.dir, "../../../../docs/features/connector.md"), "utf8");
  const section = doc.split("### 커리어 플러그인 오류 코드")[1]?.split(/\n#{2,3} /)[0];
  expect(section).toBeDefined();
  const expected: Record<string, string> = {};
  for (const line of section!.split("\n")) {
    const cells = line.split("|").map((cell) => cell.trim());
    // 표의 줄은 "| 코드 | 조건 | 어휘 |" 라서 칸 세 개 앞뒤에 빈 칸이 있다.
    if (cells.length !== 5) continue;
    const code = cells[1]!.match(/^`([A-Z_]+)`$/)?.[1];
    const vocabulary = cells[3]!.match(/^`([a-z_]+)`$/)?.[1];
    if (code && vocabulary) expected[code] = vocabulary;
  }
  expect(Object.keys(expected).length).toBeGreaterThan(0);
  expect(errorCategories).toEqual(expected);
});

// 대화용 스킬 디렉터리는 없다. 스킬은 모두 `agent-skills/` 로 설치된다.
// 키를 빼면 설치하는 쪽이 기본값 `./skills` 를 읽으므로 빈 목록으로 둔다.
test("plugin.json 의 skills 는 빈 목록이고 대화용 스킬 디렉터리가 없다", () => {
  expect(read(".claude-plugin/plugin.json").skills).toEqual([]);
  expect(existsSync(join(import.meta.dir, "..", "connector-skills"))).toBe(false);
});

test("원티드 도구 설명이 나눠 부르기, 범위 밖 칸, 다시 부르지 않기를 안내하고 지운 노트북 스킬을 안내하지 않는다", () => {
  const update = manifest.tools.update_wanted_resume.description;
  const read = manifest.tools.get_wanted_resume.description;
  expect(update).toContain("나눠");
  expect(update).not.toContain("노트북 Claude Code");
  expect(update).toContain("outOfScope");
  expect(update).toContain("자동으로 다시 부르지 않습니다");
  expect(read).not.toContain("노트북 Claude Code");
  expect(read).toContain("outOfScope");
  expect(manifest.tools.update_wanted_resume).toMatchObject({ risk: "WRITE", approval: "required", outbound: true, grant: false });
  expect(manifest.tools.update_wanted_resume).not.toHaveProperty("conversation_grant");
  expect(manifest.tools.get_wanted_resume).toMatchObject({ risk: "READ", approval: "none" });
  expect(manifest.owner_browser_login_url).toBe("https://www.wanted.co.kr/cv/list");
});

// fos-assistant 는 단추를 아는 provider 에만 「GitHub 으로 연결」 을 보이고 그 칸을 비워 둔다. 그래서 칸마다 선택 비밀 칸이어야 한다.
// 대시보드 plugin 의 connector_manifest 는 선택 칸에만 빈 기본값(`${NAME:-}`)을 받는다.
// owner_*_env 를 포함한 나머지 서버 env 는 정확히 `${NAME}` 이어야 하고, 어기면 플러그인이 카탈로그에서 빠진다.
test(".mcp.json 의 빈 기본값 참조는 선택 칸에만 쓴다", () => {
  const optional = new Set(
    manifest.fields.filter((f: { required?: boolean }) => f.required === false).map((f: { env: string }) => f.env),
  );
  for (const [name, value] of Object.entries(mcp.mcpServers.career.env))
    expect({ name, value }).toEqual({ name, value: optional.has(name) ? `\${${name}:-}` : `\${${name}}` });
});

test("oauth 선언은 GitHub 단추로 선택 비밀 칸 github_token 을 채우고 통로 주소 env 는 다른 env 와 겹치지 않는다", () => {
  expect(manifest.oauth).toEqual({
    provider: "github",
    fields: ["github_token"],
    token_env: "CAREER_GITHUB_TOKEN_URL",
  });
  for (const key of manifest.oauth.fields) {
    const field = manifest.fields.find((f: { key: string }) => f.key === key);
    expect(field).toMatchObject({ secret: true, required: false });
  }
  const others = [...manifest.fields.map((f: { env: string }) => f.env), ...manifest.operator_env, manifest.owner_browser_env];
  expect(others).not.toContain(manifest.oauth.token_env);
});
