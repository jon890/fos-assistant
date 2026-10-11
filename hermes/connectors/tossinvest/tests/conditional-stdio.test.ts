import { expect, test } from "bun:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { buildBundle } from "../scripts/build.ts";
import manifest from "../connector.json";
import canonical from "./fixtures/openapi-1.2.24.json";
import { FakeToss, accountNo, credentials, tool } from "./support.ts";

const path = "/api/v1/conditional-orders";
const detail =
  canonical.paths["/api/v1/conditional-orders/{conditionalOrderId}"].get
    .responses["200"].content["application/json"].example;
const page =
  canonical.paths[path].get.responses["200"].content["application/json"]
    .example;

test("standalone stdio의 설명·READ 정책·조건조회 GET과 계좌 마스킹을 확인한다", async () => {
  const fake = new FakeToss(
    new Set([
      "issueOAuth2Token",
      "getAccounts",
      "getConditionalOrders",
      "getConditionalOrder",
    ]),
  );
  const directory = await mkdtemp(join(tmpdir(), "conditional-stdio-"));
  const client = new Client({ name: "conditional-stdio-test", version: "1" });
  const id = detail.result.conditionalOrderId;
  const extension = {
    accountNo,
    token: credentials.TOSSINVEST_CLIENT_SECRET,
    nested: [{ access_token: credentials.TOSSINVEST_CLIENT_ID }],
  };
  const extendedDetail = {
    ...detail,
    metadata: extension,
    result: {
      ...detail.result,
      ...extension,
      first: { ...detail.result.first, ...extension },
    },
  };
  const extendedPage = {
    ...page,
    metadata: extension,
    result: {
      ...page.result,
      ...extension,
      conditionalOrders: page.result.conditionalOrders.map((row) => ({
        ...row,
        ...extension,
        first: { ...row.first, ...extension },
        ...(row.second ? { second: { ...row.second, ...extension } } : {}),
      })),
    },
  };
  fake.on("GET", path, extendedPage);
  fake.on("GET", `${path}/${encodeURIComponent(id)}`, extendedDetail);
  fake.on("GET", "/api/v1/accounts", {
    result: [{ accountSeq: 7, accountType: "BROKERAGE", accountNo }],
  });
  try {
    await buildBundle(directory);
    const preload = join(directory, "loopback.ts");
    // 고정 production URL은 그대로 두고 시험 프로세스의 전송만 loopback으로 보낸다.
    await writeFile(
      preload,
      `const original = globalThis.fetch; globalThis.fetch = ((input, init) => { const url = String(input); if (!url.startsWith('https://openapi.tossinvest.com/')) throw new Error('unexpected-origin'); return original(url.replace('https://openapi.tossinvest.com', ${JSON.stringify(fake.url)}), init); });`,
    );
    await client.connect(
      new StdioClientTransport({
        command: process.execPath,
        args: ["--preload", preload, join(directory, "tossinvest-mcp.js")],
        env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "9223372036854775807" },
        stderr: "pipe",
      }),
    );
    const tools = (await client.listTools()).tools;
    expect(manifest.schema).toBe(2);
    expect(manifest.single_binding).toBe(true);
    expect(manifest.sandbox_required).toBe(true);
    expect(manifest.default_tool_policy).toBe("deny");
    expect(new Set(tools.map(({ name }) => name))).toEqual(
      new Set([
        "list_accounts",
        "get_quotes",
        "get_holdings",
        "get_buying_power",
        "list_orders",
        "get_sellable_quantity",
        "list_conditional_orders",
        "get_conditional_order",
      ]),
    );
    expect(new Set(tools.map(({ name }) => name))).toEqual(
      new Set(Object.keys(manifest.tools)),
    );
    for (const entry of tools) {
      expect(entry.description).not.toContain("undefined");
      expect(entry.description!.length).toBeGreaterThan(20);
      expect(entry.annotations?.readOnlyHint).toBe(true);
    }
    for (const name of [
      "list_conditional_orders",
      "get_conditional_order",
    ] as const) {
      expect(manifest.tools[name]).toEqual({ risk: "READ", approval: "none" });
      const entry = tools.find((entry) => entry.name === name)!;
      expect(entry.description).toContain("연결한 계좌");
      expect(entry.inputSchema.additionalProperties).toBe(false);
      for (const key of ["account", "accountSeq", "token", "url"])
        expect(entry.inputSchema.properties).not.toHaveProperty(key);
    }
    expect(
      tools.find(({ name }) => name === "list_conditional_orders")!.description,
    ).toContain("기본 20건, 최대 100건");
    expect(
      tools.find(({ name }) => name === "get_conditional_order")!.description,
    ).toContain("최상위 상태와 각 leg 상태");
    // caller가 계좌나 credential, 목적지와 ID 경로를 바꿔도 인증부터 보내지 않는다.
    for (const [name, args] of [
      ["list_conditional_orders", { status: "OPEN", account: "8" }],
      ["list_conditional_orders", { status: "OPEN", token: "caller-token" }],
      [
        "get_conditional_order",
        { conditionalOrderId: id, url: "https://invalid.example" },
      ],
      ...[".", "..", "%2e%2e", "a/b"].map((conditionalOrderId) => [
        "get_conditional_order",
        { conditionalOrderId },
      ]),
    ] as Array<[string, Record<string, unknown>]>)
      expect((await client.callTool({ name, arguments: args })).isError).toBe(
        true,
      );
    expect(fake.requests).toHaveLength(0);
    for (const status of ["OPEN", "CLOSED"]) {
      const listing = await tool(client, "list_conditional_orders", { status });
      expect(listing.result.isError).toBeUndefined();
      expect(listing.body).toEqual({
        result: page.result,
        metadata: {},
        ...page.result,
      });
      expect(listing.result.structuredContent).toEqual(listing.body);
    }
    const single = await tool(client, "get_conditional_order", {
      conditionalOrderId: id,
    });
    expect(single.result.isError).toBeUndefined();
    expect(single.body).toEqual({ result: detail.result, metadata: {} });
    expect(single.result.structuredContent).toEqual(single.body);
    const accounts = await tool(client, "list_accounts");
    expect(accounts.result.isError).toBeUndefined();
    expect(accounts.body.result).toMatchObject([{ accountNo: "****8901" }]);
    expect(JSON.stringify(accounts.result)).not.toContain(accountNo);
    const gets = fake.seen("GET");
    expect(gets).toHaveLength(4);
    expect(
      gets.slice(0, 2).map((request) => Object.fromEntries(request.query)),
    ).toEqual([
      { status: "OPEN", limit: "20" },
      { status: "CLOSED", limit: "20" },
    ]);
    expect(gets[2]!.path).toBe(`${path}/${encodeURIComponent(id)}`);
    for (const request of gets.slice(0, 3)) {
      expect(request.headers.get("x-tossinvest-account")).toBe(
        "9223372036854775807",
      );
      expect(request.headers.get("authorization")).toBe(
        "Bearer fake-access-token-1",
      );
      expect(request.body).toBe("");
    }
    expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
    expect(
      fake.requests.filter(
        (request) =>
          request.method === "DELETE" ||
          (request.method === "POST" && request.path !== "/oauth2/token"),
      ),
    ).toHaveLength(0);
  } finally {
    await client.close();
    fake.stop();
    await rm(directory, { recursive: true, force: true });
  }
});
