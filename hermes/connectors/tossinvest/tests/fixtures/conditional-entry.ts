import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { mkdtemp, cp, mkdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve, join } from "node:path";
import { Tossinvest, type TossinvestOptions } from "../../src/client.ts";
import { createToolRegistration } from "../../src/tool-registration.ts";
import { registerReadTools } from "../../src/read-tools.ts";
import { registerAccountTools } from "../../src/account-tools.ts";
import { registerConditionalReadTools } from "../../src/conditional-read-tools.ts";
import { registerConditionalTradingTools } from "../../src/conditional-tools.ts";
import { CONDITIONAL_OPERATIONS } from "../../src/conditional-body.ts";
import { requireLoopback } from "./trading-entry.ts";

export { ClaimFixture, requireLoopback } from "./trading-entry.ts";

/** 조건주문 세 쓰기를 등록한 테스트 서버다. 운영 createTossinvestServer 는 이 등록을 하지 않는다. */
export function createConditionalFixtureServer(options: TossinvestOptions) {
  requireLoopback(options.apiBase!);
  if (options.env?.FOS_APPROVAL_CLAIM_URL)
    requireLoopback(options.env.FOS_APPROVAL_CLAIM_URL);
  const client = new Tossinvest(options);
  const server = new McpServer({ name: "fos-tossinvest", version: "1.0.0" });
  const register = createToolRegistration(server);
  registerReadTools(register, client);
  registerAccountTools(register, client);
  registerConditionalReadTools(register, client);
  registerConditionalTradingTools(register, client);
  return server;
}

/**
 * Bun 테스트와 Python fixture 가 이 명령 한 곳으로 같은 테스트 설치 원본을 만든다.
 * 금융 선언과 `kinds` 는 고유 임시 디렉터리의 사본에만 넣고 운영 assistant.json 은 바꾸지 않는다.
 */
export async function generateConditionalFixture(destination: string) {
  requireLoopback(destination);
  const root = await mkdtemp(join(tmpdir(), "fos-toss-conditional-"));
  const source = resolve(import.meta.dir, "../..");
  for (const name of [".claude-plugin", ".mcp.json", "icon.png", "skills"])
    await cp(join(source, name), join(root, name), { recursive: true });
  const manifest = JSON.parse(
    await readFile(join(source, "assistant.json"), "utf8"),
  );
  manifest.tools.prepare_conditional_trade = { risk: "READ" };
  for (const tool of Object.keys(CONDITIONAL_OPERATIONS))
    manifest.tools[tool] = { risk: "FINANCIAL", approval: "always" };
  manifest.execution_guard = {
    protocol: "approval-claim-v2",
    prepare_tool: "prepare_conditional_trade",
    scope_fields: [{ arg: "account_seq", field: "account" }],
    operations: CONDITIONAL_OPERATIONS,
    kinds: Object.fromEntries(
      Object.keys(CONDITIONAL_OPERATIONS).map((tool) => [tool, "CONDITIONAL"]),
    ),
  };
  await writeFile(
    join(root, "assistant.json"),
    JSON.stringify(manifest, null, 2) + "\n",
  );
  const mcp = JSON.parse(await readFile(join(root, ".mcp.json"), "utf8"));
  mcp.mcpServers.tossinvest.args.push("--serve", destination);
  await writeFile(join(root, ".mcp.json"), JSON.stringify(mcp, null, 2) + "\n");
  await mkdir(join(root, "dist"));
  const built = await Bun.build({
    entrypoints: [resolve(import.meta.path)],
    target: "bun",
    format: "esm",
    minify: true,
    outdir: join(root, "dist"),
    naming: "tossinvest-mcp.js",
  });
  if (!built.success) throw new Error("TEST_CONDITIONAL_BUILD_FAILED");
  return root;
}

if (import.meta.main) {
  if (process.argv[2] === "--generate") {
    process.stdout.write(
      (await generateConditionalFixture(
        process.argv[3] ?? "http://127.0.0.1:9",
      )) + "\n",
    );
  } else if (process.argv[2] === "--serve") {
    const destination = requireLoopback(process.argv[3]!);
    // SDK 자식의 모든 fetch 를 확인한다. 금융 목적지와 claim 모두 loopback 만 허용한다.
    const original = globalThis.fetch;
    globalThis.fetch = ((input: any, init: any) => {
      requireLoopback(input instanceof Request ? input.url : String(input));
      return original(input, init);
    }) as typeof fetch;
    await createConditionalFixtureServer({
      apiBase: destination.origin,
      timeoutMs: 2000,
    }).connect(new StdioServerTransport());
  } else throw new Error("TEST_CONDITIONAL_ENTRY_ARGUMENTS_REJECTED");
}
