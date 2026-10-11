import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { expect, test } from "bun:test";
import manifest from "../connector.json";

test("커밋한 stdio bundle의 SDK 목록은 manifest 정본 설명과 같다", async () => {
  const client = new Client({ name: "description-contract", version: "1.0.0" });
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [new URL("../dist/naver-blog-mcp.js", import.meta.url).pathname],
    env: {},
    stderr: "pipe",
  });
  try {
    await client.connect(transport);
    const tools = (await client.listTools()).tools;
    const declared: Record<string, { description: string }> = manifest.tools;
    expect(new Set(tools.map((tool) => tool.name))).toEqual(
      new Set(Object.keys(declared)),
    );
    for (const tool of tools)
      expect(tool.description).toBe(declared[tool.name]!.description);
    expect(tools.filter((tool) => !tool.description?.trim())).toEqual([]);
  } finally {
    await client.close();
    await transport.close();
  }
});
