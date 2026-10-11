import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { expect, test } from "bun:test";
import { join } from "node:path";

// 테스트 명령이 만든 실제 entry를 실행한다. 계정 없이 목록만 읽는다.
test("빌드한 entry의 stdio 도구 목록은 manifest와 정확히 같다", async () => {
  const root = join(import.meta.dir, "..");
  const manifest = await Bun.file(join(root, "connector.json")).json();
  const client = new Client({ name: "bundle-contract-test", version: "1.0.0" });
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: ["--no-env-file", join(root, "dist/tossinvest-mcp.js")],
    env: {},
    stderr: "pipe",
  });
  try {
    await client.connect(transport);
    const tools = (await client.listTools()).tools;
    expect(tools.map((tool) => tool.name).sort()).toEqual(
      Object.keys(manifest.tools).sort(),
    );
    for (const tool of tools) {
      expect(tool.inputSchema.type).toBe("object");
      expect(tool.annotations?.readOnlyHint === true).toBe(
        manifest.tools[tool.name].risk === "READ",
      );
    }
  } finally {
    await client.close();
  }
});
