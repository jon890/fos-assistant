import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { mkdtemp, cp, mkdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve, join } from "node:path";
import { Tossinvest, type TossinvestOptions } from "../../src/client.ts";
import { createToolRegistration } from "../../src/tool-registration.ts";
import { registerTradingTools } from "../../src/trading-tools.ts";
import { registerReadTools } from "../../src/read-tools.ts";
import { registerAccountTools } from "../../src/account-tools.ts";
import { TRADE_OPERATIONS } from "../../src/trading-body.ts";
import { randomUUID } from "node:crypto";
import { sha256, strictJson } from "../../src/execution-guard.ts";

/** 실제 서명 인증·DB 소비를 대신하지 않는 protocol 대역이다. */
export class ClaimFixture {
  readonly requests: any[] = [];
  readonly issued = new Map<string, any>();
  readonly consumed = new Set<string>();
  respond?: (body: any) => Response | Promise<Response>;
  readonly server = Bun.serve({
    hostname: "127.0.0.1",
    port: 0,
    fetch: async (request) => {
      let body: any;
      try {
        body = strictJson(await request.text(), 8192);
        this.requests.push(body);
        if (!Object.hasOwn(body, "ticket"))
          return new Response("{}", { status: 401 });
        if (Object.keys(body).sort().join() !== "argsSha256,scope,ticket,tool")
          return new Response("{}", { status: 400 });
      } catch {
        return new Response("{}", { status: 400 });
      }
      if (this.respond) return this.respond(body);
      const payload = this.issued.get(body.ticket);
      if (!payload) return new Response("{}", { status: 401 });
      if (
        this.consumed.has(body.ticket) ||
        payload.expiresAt <= new Date().toISOString() ||
        body.tool !== payload.tool ||
        body.argsSha256 !== payload.argsSha256 ||
        sha256(JSON.stringify(body.scope)) !== payload.scopeSha256
      )
        return new Response("{}", { status: 409 });
      this.consumed.add(body.ticket);
      return Response.json({
        v: 1,
        allowed: true,
        ticketId: payload.ticketId,
        expiresAt: payload.expiresAt,
      });
    },
  });
  get url() {
    return new URL(
      "/internal/connector-executions/claim",
      this.server.url,
    ).toString();
  }
  authority(
    tool: string,
    args: Record<string, any>,
    duration = 59000,
    raw = JSON.stringify(args),
  ) {
    const payload = {
      v: 1,
      ticketId: randomUUID(),
      actionId: randomUUID(),
      userId: 901,
      agentId: 801,
      connectionId: 701,
      bindingId: 601,
      profile: "synthetic",
      connectorId: "tossinvest",
      tool,
      argsSha256: sha256(raw),
      scopeSha256: sha256(JSON.stringify({ account_seq: args.account_seq })),
      issuedAt: new Date(Date.now() - 100).toISOString(),
      expiresAt: new Date(Date.now() + duration).toISOString(),
    };
    const ticket =
      Buffer.from(JSON.stringify(payload)).toString("base64url") +
      "." +
      Buffer.alloc(32).toString("base64url");
    this.issued.set(ticket, payload);
    return {
      FOS_APPROVAL_TICKET: ticket,
      FOS_APPROVAL_ARGS_JSON: raw,
      FOS_APPROVAL_CLAIM_URL: this.url,
    };
  }
  stop() {
    this.server.stop(true);
  }
}

export function requireLoopback(raw: string) {
  const url = new URL(raw);
  if (
    url.protocol !== "http:" ||
    !["127.0.0.1", "[::1]"].includes(url.hostname) ||
    url.username ||
    url.password
  )
    throw new Error("TEST_NON_LOOPBACK_REJECTED");
  return url;
}
export function createTradingFixtureServer(options: TossinvestOptions) {
  requireLoopback(options.apiBase!);
  if (options.env?.FOS_APPROVAL_CLAIM_URL)
    requireLoopback(options.env.FOS_APPROVAL_CLAIM_URL);
  const client = new Tossinvest(options);
  const server = new McpServer({ name: "fos-tossinvest", version: "1.0.0" });
  const register = createToolRegistration(server);
  registerReadTools(register, client);
  registerAccountTools(register, client);
  registerTradingTools(register, client);
  return server;
}

/** Bun과 후속 Python fixture가 이 명령 한 곳으로 같은 테스트 설치 원본을 만든다. */
export async function generateTradingFixture(destination: string) {
  requireLoopback(destination);
  const root = await mkdtemp(join(tmpdir(), "fos-toss-trading-"));
  const source = resolve(import.meta.dir, "../..");
  for (const name of [".claude-plugin", ".mcp.json", "icon.png", "skills"])
    await cp(join(source, name), join(root, name), { recursive: true });
  const manifest = JSON.parse(
    await readFile(join(source, "connector.json"), "utf8"),
  );
  manifest.tools.prepare_trade = { risk: "READ" };
  for (const tool of Object.keys(TRADE_OPERATIONS))
    manifest.tools[tool] = { risk: "FINANCIAL", approval: "always" };
  manifest.execution_guard = {
    protocol: "approval-claim-v1",
    prepare_tool: "prepare_trade",
    scope_fields: [{ arg: "account_seq", field: "account" }],
    operations: TRADE_OPERATIONS,
  };
  await writeFile(
    join(root, "connector.json"),
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
  if (!built.success) throw new Error("TEST_TRADING_BUILD_FAILED");
  return root;
}

if (import.meta.main) {
  if (process.argv[2] === "--generate") {
    process.stdout.write(
      (await generateTradingFixture(process.argv[3] ?? "http://127.0.0.1:9")) +
        "\n",
    );
  } else if (process.argv[2] === "--serve") {
    const destination = requireLoopback(process.argv[3]!);
    // SDK 자식의 모든 fetch를 확인한다. 금융 목적지·claim 모두 loopback만 허용한다.
    const original = globalThis.fetch;
    globalThis.fetch = ((input: any, init: any) => {
      requireLoopback(input instanceof Request ? input.url : String(input));
      return original(input, init);
    }) as typeof fetch;
    await createTradingFixtureServer({
      apiBase: destination.origin,
      timeoutMs: 2000,
    }).connect(new StdioServerTransport());
  } else throw new Error("TEST_TRADING_ENTRY_ARGUMENTS_REJECTED");
}
