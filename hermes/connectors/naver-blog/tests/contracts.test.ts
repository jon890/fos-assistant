import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { CHANGES_MAX } from "../src/overwrite-draft.ts";
import { createServer, isSupportedBunVersion } from "../src/server.ts";
import { BLOG_ID_PATTERN, BROWSER_URL_PATTERN } from "../src/session.ts";

const root = join(import.meta.dir, "..");
const manifest = JSON.parse(readFileSync(join(root, "connector.json"), "utf8"));
const CONNECTOR_SCHEMA = join(root, "../../plugins/dashboard-profile-api/connector_schema.py");

async function listTools() {
  const client = new Client({ name: "naver-blog-test", version: "1.0.0" });
  const server = createServer({});
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    return (await client.listTools()).tools;
  } finally {
    await client.close();
    await server.close();
  }
}

test("manifest 는 확인 도구와 기본 거절 정책을 유지한다", () => {
  expect(manifest.id).toBe("naver-blog");
  expect(manifest.verify).toEqual({ tool: "session_status" });
  expect(manifest.default_tool_policy).toBe("deny");
});

test("서버가 다시 검사하는 정규식은 연결 칸의 정규식과 같다", () => {
  const pattern = (key: string) =>
    new RegExp(manifest.fields.find((field: { key: string }) => field.key === key).pattern).source;

  expect(BLOG_ID_PATTERN.source).toBe(pattern("blog_id"));
});

test("중계 주소 정규식은 대시보드가 설치할 때 쓰는 OWNER_BROWSER_VALUE_RE 와 같은 식이다", () => {
  const schema = readFileSync(CONNECTOR_SCHEMA, "utf8");
  const declared = /^OWNER_BROWSER_VALUE_RE = re\.compile\(r"(.+)"\)$/m.exec(schema)?.[1];
  // JS 의 source 는 `/` 를 `\/` 로 적는다. 그 이스케이프만 풀어 Python 글과 견준다.
  const ours = BROWSER_URL_PATTERN.source.replaceAll("\\/", "/");

  expect(declared).toBeDefined();
  expect(ours).toBe(declared!);
});

test("중계 주소 env 와 로그인 안내 주소를 선언하고 연결 칸에는 블로그 아이디만 둔다", () => {
  expect(manifest.owner_browser_env).toBe("NAVER_BLOG_BROWSER_URL");
  expect(manifest.owner_browser_login_url).toBe("https://nid.naver.com/nidlogin.login");
  expect(manifest.fields.map((field: { key: string }) => field.key)).toEqual(["blog_id"]);
});

test("manifest 도구 선언은 MCP 서버 도구와 정확히 같다", async () => {
  const tools = await listTools();

  expect(new Set(tools.map((tool) => tool.name))).toEqual(new Set(Object.keys(manifest.tools)));
});

test("도구 일곱을 선언하고 임시저장과 덮어쓰기만 승인 카드 제목이 있는 계정 안 쓰기다", async () => {
  const tools = await listTools();

  expect(tools.map((tool) => tool.name).sort()).toEqual([
    "draft_job",
    "list_drafts",
    "overwrite_draft",
    "read_draft",
    "render_draft",
    "save_draft",
    "session_status",
  ]);
  expect(manifest.tools.list_drafts).toEqual({ risk: "READ" });
  expect(manifest.tools.read_draft).toEqual({ risk: "READ" });
  expect(manifest.tools.save_draft).toEqual({
    risk: "WRITE",
    title: "네이버 블로그에 임시저장",
    outbound: false,
  });
  // 덮어쓰기는 사용자가 쓴 글을 바꾸므로 매번 승인 카드를 거치게 상시 허락을 닫는다.
  expect(manifest.tools.overwrite_draft).toEqual({
    risk: "WRITE",
    title: "네이버 블로그 임시저장 글 덮어쓰기",
    outbound: false,
    grant: false,
  });
  expect(manifest.tools.draft_job).toEqual({ risk: "READ" });
});

test("overwrite_draft 는 일곱 칸을 받고 changes 를 1자부터 CHANGES_MAX 자까지 받는다", async () => {
  const tools = await listTools();
  const schema = tools.find((tool) => tool.name === "overwrite_draft")!.inputSchema as {
    properties: Record<string, Record<string, unknown>>;
    required: string[];
  };

  expect(Object.keys(schema.properties).sort()).toEqual(
    ["body", "category", "changes", "draft_id", "revision", "tags", "title"],
  );
  expect([...schema.required].sort()).toEqual(
    ["body", "category", "changes", "draft_id", "revision", "tags", "title"],
  );
  expect(schema.properties.changes).toMatchObject({ minLength: 1, maxLength: CHANGES_MAX });
  expect(schema.properties.revision).toMatchObject({ pattern: "^[0-9a-f]{16}$" });
});

test("작업 오류 코드는 공통 어휘에 이어진다", () => {
  expect(manifest.errors).toMatchObject({
    NAVER_BLOG_BUSY: "unavailable",
    NAVER_BLOG_JOB_NOT_FOUND: "invalid_input",
    NAVER_BLOG_DRAFT_NOT_FOUND: "invalid_input",
    NAVER_BLOG_EDITOR_IN_USE: "unavailable",
    NAVER_BLOG_START_UNKNOWN: "outcome_unknown",
  });
});

test("READ 도구만 readOnlyHint 가 참이고 모든 도구에 설명이 있다", async () => {
  const tools = await listTools();

  const readOnly = tools.filter((tool) => tool.annotations?.readOnlyHint === true).map((tool) => tool.name);
  const declaredRead = Object.entries(manifest.tools)
    .filter(([, spec]) => (spec as { risk: string }).risk === "READ")
    .map(([name]) => name);
  expect(new Set(readOnly)).toEqual(new Set(declaredRead));
  for (const tool of tools) expect(tool.description?.trim().length).toBeGreaterThan(20);
});

test.each([
  ["1.3.13", false],
  ["1.3.14-canary.1", false],
  ["1.3.14", true],
  ["1.4.0", true],
  ["2.0.0", true],
])("Bun %s 의 최소 1.3.14 지원 여부를 숫자로 비교한다", (version, expected) => {
  expect(isSupportedBunVersion(version)).toBe(expected);
});
