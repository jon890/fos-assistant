import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { createGmailServer } from "../src/server.ts";
import { withMcp } from "./support.ts";

const root = join(import.meta.dir, "..");
const manifest = JSON.parse(readFileSync(join(root, "connector.json"), "utf8"));

test("manifest는 dashboard 필수 필드와 Gmail client ID pattern을 유지한다", () => {
  expect(manifest.id).toBe("gmail");
  expect(manifest.verify).toEqual({ tool: "get_profile" });
  expect(manifest.default_tool_policy).toBe("deny");
  expect(
    manifest.fields.find((field: { key: string }) => field.key === "client_id")
      .pattern,
  ).toBe("^[0-9A-Za-z_-]+\\.apps\\.googleusercontent\\.com$");
});

test("manifest 도구 선언은 MCP 서버 도구와 정확히 일치한다", async () => {
  const server = createGmailServer({ env: {} });
  await withMcp(server, async (client) => {
    expect(
      new Set((await client.listTools()).tools.map((tool) => tool.name)),
    ).toEqual(new Set(Object.keys(manifest.tools)));
  });
});

test("모든 MCP 도구는 사용자가 판단할 수 있는 설명을 제공한다", async () => {
  const server = createGmailServer({ env: {} });
  await withMcp(server, async (client) => {
    const tools = await client.listTools();
    expect(tools.tools).toHaveLength(15);
    for (const tool of tools.tools) {
      expect(tool.description?.trim().length).toBeGreaterThan(20);
    }
    const byName = new Map(
      tools.tools.map((tool) => [tool.name, tool.description ?? ""]),
    );
    expect(byName.get("search_messages")).toMatch(/검색|search/i);
    expect(byName.get("modify_labels")).toMatch(/보관|archive|라벨|label/i);
    expect(byName.get("send_message")).toMatch(/결과|unknown|확인/i);
    expect(byName.get("apply_labels_to_query")).toMatch(
      /expected_count|승인|개수/i,
    );
  });
});

test("Gmail 서버 소스에는 메일 삭제·trash·attachment download endpoint가 없다", () => {
  const source = readFileSync(join(root, "src/server.ts"), "utf8");
  for (const blocked of ["batchDelete", "/trash", "/attachments/"])
    expect(source).not.toContain(blocked);
  expect(source).not.toMatch(/\/messages[^\n]*"DELETE"/);
});
