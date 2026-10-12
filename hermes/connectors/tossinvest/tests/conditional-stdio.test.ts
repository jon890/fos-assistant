import { expect, test } from "bun:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import {
  boundaryCases,
  withExtensions,
  sensitiveExtension,
  sensitiveMarker,
} from "./conditional-boundary-fixture.ts";
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
        "get_buying_power",
        "get_candles",
        "get_commissions",
        "get_conditional_order",
        "get_exchange_rate",
        "get_holdings",
        "get_market_calendar",
        "get_market_indicator_candles",
        "get_market_indicator_investor_trading",
        "get_market_indicator_prices",
        "get_order",
        "get_orderbook",
        "get_price_limits",
        "get_quotes",
        "get_rankings",
        "get_sector",
        "get_sector_etfs",
        "get_sector_rankings",
        "get_sector_stocks",
        "get_sellable_quantity",
        "get_stock_credit_trades",
        "get_stock_investor_trading",
        "get_stock_program_trades",
        "get_stock_securities_lending",
        "get_stock_short_selling",
        "get_stock_warnings",
        "get_stocks",
        "get_trades",
        "list_accounts",
        "list_conditional_orders",
        "list_orders",
        "list_sectors",
        "list_stocks",
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
      expect(manifest.tools[name]).toEqual({
        risk: "READ",
        approval: "none",
        title: expect.any(String),
        summary: expect.any(String),
        description: tools.find((entry) => entry.name === name)!.description!,
      });
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
    const listSchema = tools.find(
      ({ name }) => name === "list_conditional_orders",
    )!.inputSchema;
    const detailSchema = tools.find(
      ({ name }) => name === "get_conditional_order",
    )!.inputSchema;
    expect(listSchema.properties?.status).toMatchObject({
      description: "필수. OPEN 또는 CLOSED를 넘긴다.",
      default: "",
    });
    expect(listSchema.properties?.limit).toMatchObject({
      description: "정수 1..100. 생략하면 기본 20건.",
    });
    expect(detailSchema.properties?.conditionalOrderId).toMatchObject({
      description: "필수. 조회할 조건주문의 불투명 ID를 넘긴다.",
      default: "",
    });
    expect(listSchema.required ?? []).not.toContain("status");
    expect(detailSchema.required ?? []).not.toContain("conditionalOrderId");
    expect(
      tools.find(({ name }) => name === "get_conditional_order")!.description,
    ).toContain("targetProfitRate 문자열 10.5는 10.5%");
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
    for (const code of [
      ...Array.from({ length: 32 }, (_, index) => index),
      ...Array.from({ length: 33 }, (_, index) => 127 + index),
    ]) {
      const rejected = await tool(client, "get_conditional_order", {
        conditionalOrderId: `a${String.fromCharCode(code)}b`,
      });
      expect(rejected.result.isError).toBe(true);
      expect(rejected.body).toEqual({
        error: { code: "TOSSINVEST_INVALID_INPUT" },
      });
      expect(fake.requests).toHaveLength(0);
    }
    for (const [name, args] of [
      ["list_conditional_orders", {}],
      ["get_conditional_order", {}],
      ["list_conditional_orders", { status: "OPEN", limit: 1.5 }],
    ] as Array<[string, Record<string, unknown>]>) {
      const rejected = await tool(client, name, args);
      expect(rejected.result.isError).toBe(true);
      expect(rejected.body).toEqual({
        error: { code: "TOSSINVEST_INVALID_INPUT" },
      });
      expect(fake.requests).toHaveLength(0);
    }
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
    const longId = `opaque-9223372036854775808-${"x".repeat(4096)}-가😀`;
    fake.on("GET", `${path}/${encodeURIComponent(longId)}`, extendedDetail);
    const long = await tool(client, "get_conditional_order", {
      conditionalOrderId: longId,
    });
    expect(long.result.isError).toBeUndefined();
    expect(long.body).toEqual({ result: detail.result, metadata: {} });
    expect(fake.seen("GET").at(-1)!.path).toBe(
      `${path}/${encodeURIComponent(longId)}`,
    );
    for (const expected of boundaryCases()) {
      const upstream = withExtensions(expected);
      fake.on("GET", `${path}/${encodeURIComponent(id)}`, {
        result: upstream,
        metadata: sensitiveExtension,
        ...sensitiveExtension,
      });
      const next = {
        ...expected,
        conditionalOrderId: "next-opaque-9223372036854775808",
      };
      const expectedPage = {
        conditionalOrders: [expected, next],
        nextCursor: null,
        hasNext: false,
      };
      fake.on("GET", path, {
        result: {
          ...expectedPage,
          ...sensitiveExtension,
          conditionalOrders: [upstream, withExtensions(next)],
        },
        metadata: sensitiveExtension,
        ...sensitiveExtension,
      });
      const singleVariant = await tool(client, "get_conditional_order", {
        conditionalOrderId: id,
      });
      const pageVariant = await tool(client, "list_conditional_orders", {
        status: "CLOSED",
      });
      expect(singleVariant.result.isError).toBeUndefined();
      expect(singleVariant.body).toEqual({ result: expected, metadata: {} });
      expect(pageVariant.result.isError).toBeUndefined();
      expect(pageVariant.body).toEqual({
        result: expectedPage,
        metadata: {},
        ...expectedPage,
      });
      for (const response of [singleVariant, pageVariant]) {
        expect(response.result.structuredContent).toEqual(response.body);
        expect(JSON.stringify(response.result)).not.toContain(sensitiveMarker);
        expect(JSON.stringify(response.result)).not.toContain("access_token");
      }
    }
    expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
    expect(fake.seen("GET")).toHaveLength(13);
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
