import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { createServer, isSupportedBunVersion } from "../src/server.ts";
import { BLOG_ID_PATTERN, CDP_URL_PATTERN } from "../src/session.ts";

const root = join(import.meta.dir, "..");
const manifest = JSON.parse(readFileSync(join(root, "connector.json"), "utf8"));

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

  expect(CDP_URL_PATTERN.source).toBe(pattern("cdp_url"));
  expect(BLOG_ID_PATTERN.source).toBe(pattern("blog_id"));
});

test("manifest 도구 선언은 MCP 서버 도구와 정확히 같다", async () => {
  const tools = await listTools();

  expect(new Set(tools.map((tool) => tool.name))).toEqual(new Set(Object.keys(manifest.tools)));
});

test("도구 넷을 선언하고 임시저장만 승인 카드 제목이 있는 계정 안 쓰기다", async () => {
  const tools = await listTools();

  expect(tools.map((tool) => tool.name).sort()).toEqual(
    ["draft_job", "render_draft", "save_draft", "session_status"],
  );
  expect(manifest.tools.save_draft).toEqual({
    risk: "WRITE",
    title: "네이버 블로그에 임시저장",
    outbound: false,
  });
  expect(manifest.tools.draft_job).toEqual({ risk: "READ" });
});

test("작업 오류 코드는 공통 어휘에 이어진다", () => {
  expect(manifest.errors).toMatchObject({
    NAVER_BLOG_BUSY: "unavailable",
    NAVER_BLOG_JOB_NOT_FOUND: "invalid_input",
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
