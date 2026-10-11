import { expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { buildBundle } from "../scripts/build.ts";
import {
  OPERATIONS,
  CURRENT_OPERATIONS,
  CONTRACT_SHA256,
  matchesOperation,
  parseApiResponse,
} from "../src/api-contract.ts";
import { createTossinvestServer } from "../src/server.ts";
import {
  FakeToss,
  emptyHoldings,
  credentials,
  tool,
  withMcp,
} from "./support.ts";

const raw = readFileSync(`${import.meta.dir}/fixtures/openapi-1.2.24.json`);
export const canonical = JSON.parse(raw.toString());

test("현재 14개 READ operation의 공식 성공 예시 36개를 그대로 읽는다", () => {
  const reads = new Set([
    "getAccounts",
    "getPrices",
    "getStocks",
    "getHoldings",
    "getBuyingPower",
    "getSellableQuantity",
    "getOrders",
    "getOrderbook",
    "getTrades",
    "getPriceLimit",
    "getCandles",
    "getExchangeRate",
    "getKrMarketCalendar",
    "getUsMarketCalendar",
  ]);
  let count = 0;
  for (const methods of Object.values(canonical.paths) as any[]) {
    const upstream = methods.get;
    if (!upstream || !reads.has(upstream.operationId)) continue;
    const operation = OPERATIONS.find(
      (item) => item.id === upstream.operationId,
    )!;
    const content = upstream.responses["200"].content["application/json"];
    const examples = content.examples
      ? Object.values(content.examples).map((example: any) => example.value)
      : [content.example];
    for (const example of examples) {
      // Account.accountSeq의 MCP 출력 계약만 문자열로 바뀐다. 공식 입력 예시는 수정하지 않는다.
      const expected =
        operation.id === "getAccounts"
          ? {
              ...example,
              result: example.result.map((row: any) => ({
                ...row,
                accountSeq: String(row.accountSeq),
              })),
            }
          : example;
      expect(parseApiResponse(JSON.stringify(example), operation, 200)).toEqual(
        expected,
      );
      count++;
    }
  }
  expect(count).toBe(36);
});

test("ETF 영문명 누락 호환은 해당 분류와 필드에만 적용한다", () => {
  const operation = OPERATIONS.find((item) => item.id === "getStocks")!;
  const example =
    canonical.paths[operation.path].get.responses["200"].content[
      "application/json"
    ].examples.etf.value;
  expect(example.result[0].securityType).toBe("ETF");
  expect(Object.hasOwn(example.result[0], "englishName")).toBe(false);
  expect(parseApiResponse(JSON.stringify(example), operation, 200)).toEqual(
    example,
  );
  for (const change of [
    { englishName: 123 },
    { englishName: null },
    { securityType: "STOCK" },
    { securityType: "FOREIGN_ETF" },
    { securityType: "UNKNOWN" },
  ]) {
    const invalid = structuredClone(example);
    Object.assign(invalid.result[0], change);
    expect(() =>
      parseApiResponse(JSON.stringify(invalid), operation, 200),
    ).toThrow();
  }
  for (const field of canonical.components.schemas.StockInfo.required.filter(
    (field: string) => field !== "englishName",
  )) {
    const invalid = structuredClone(example);
    delete invalid.result[0][field];
    expect(() =>
      parseApiResponse(JSON.stringify(invalid), operation, 200),
    ).toThrow();
  }
});

test("공식 ETF와 일반 종목 예시를 실제 SDK get_quotes로 보존한다", async () => {
  const stocks =
    canonical.paths["/api/v1/stocks"].get.responses["200"].content[
      "application/json"
    ].examples;
  const prices =
    canonical.paths["/api/v1/prices"].get.responses["200"].content[
      "application/json"
    ].examples;
  for (const example of Object.values(stocks) as any[]) {
    const fake = new FakeToss(
      new Set(["issueOAuth2Token", "getStocks", "getPrices"]),
    );
    const price = (Object.values(prices)[0] as any).value;
    fake.on("GET", "/api/v1/stocks", example.value);
    fake.on("GET", "/api/v1/prices", price);
    try {
      await withMcp(
        createTossinvestServer({ apiBase: fake.url, env: credentials }),
        async (client) => {
          const symbols = example.value.result
            .map((row: any) => row.symbol)
            .join(",");
          const { result, body } = await tool(client, "get_quotes", {
            symbols,
          });
          expect(result.isError).toBeUndefined();
          expect(result.structuredContent).toEqual(body);
          expect(body.result).toEqual({
            stocks: example.value.result,
            prices: price.result,
          });
        },
      );
      expect(fake.seen("POST")).toHaveLength(1);
      expect(fake.seen("GET")).toHaveLength(2);
    } finally {
      fake.stop();
    }
  }
});

test("공식 41 operation의 method/path/group과 SHA-256이 일치하고 미구현을 구분한다", () => {
  expect(canonical.info.version).toBe("1.2.24");
  expect(createHash("sha256").update(raw).digest("hex")).toBe(CONTRACT_SHA256);
  const expected = Object.entries(canonical.paths).flatMap(
    ([path, methods]: [string, any]) =>
      Object.entries(methods)
        .filter(([, op]: any) => op.operationId)
        .map(([method, op]: any) => ({
          id: op.operationId,
          method: method.toUpperCase(),
          path,
          group: /Rate Limits Group[^`]*`([^`]+)/.exec(op.description)![1],
        })),
  );
  expect(
    OPERATIONS.map(({ id, method, path, group }) => ({
      id,
      method,
      path,
      group,
    })),
  ).toEqual(expected);
  expect(new Set(OPERATIONS.map((op) => `${op.method} ${op.path}`)).size).toBe(
    41,
  );
  expect(new Set(OPERATIONS.map((op) => op.group)).size).toBe(19);
  expect(CURRENT_OPERATIONS.size).toBe(15);
  expect(
    OPERATIONS.filter((op) => !CURRENT_OPERATIONS.has(op.id)),
  ).toHaveLength(26);
});

test("READ 허용 집합은 금융 POST와 DELETE를 거절한다", async () => {
  for (const [method, path] of [
    ["POST", "/api/v1/orders"],
    ["DELETE", "/api/v1/conditional-orders/id"],
  ]) {
    const fake = new FakeToss(new Set(["issueOAuth2Token", "getAccounts"]));
    fake.on(method!, path!, {});
    await fetch(`${fake.url}${path}`, { method });
    expect(() => fake.stop()).toThrow();
  }
  const op = OPERATIONS.find((op) => op.id === "cancelConditionalOrder")!;
  expect(
    matchesOperation(op, "DELETE", "/api/v1/conditional-orders/id%2Fone"),
  ).toBe(true);
  expect(
    matchesOperation(op, "DELETE", "/api/v1/conditional-orders/id/one"),
  ).toBe(false);
});

test("production SDK의 12개 도구가 15 operation에 도달하고 GET body는 없다", async () => {
  const allowed = new Set([
    "issueOAuth2Token",
    "getAccounts",
    "getPrices",
    "getStocks",
    "getHoldings",
    "getBuyingPower",
    "getSellableQuantity",
    "getOrders",
    "getOrderbook",
    "getTrades",
    "getPriceLimit",
    "getCandles",
    "getExchangeRate",
    "getKrMarketCalendar",
    "getUsMarketCalendar",
  ]);
  const fake = new FakeToss(allowed);
  for (const [path, result] of Object.entries({
    accounts: [],
    prices: [],
    stocks: [],
    holdings: emptyHoldings,
    "buying-power": { currency: "KRW", cashBuyingPower: "1" },
    "sellable-quantity": { sellableQuantity: "2" },
    orders: { orders: [], nextCursor: null, hasNext: false },
  }))
    fake.on("GET", `/api/v1/${path}`, { result });
  for (const [path, example] of [
    ["orderbook", "krStock"],
    ["trades", "usStock"],
    ["price-limits", "usStock"],
    ["candles", "lastPage"],
  ]) {
    const endpoint = `/api/v1/${path}`;
    fake.on(
      "GET",
      endpoint,
      canonical.paths[endpoint].get.responses["200"].content["application/json"]
        .examples[example!].value,
    );
  }
  for (const path of [
    "exchange-rate",
    "market-calendar/KR",
    "market-calendar/US",
  ])
    fake.on(
      "GET",
      `/api/v1/${path}`,
      Object.values(
        canonical.paths[`/api/v1/${path}`].get.responses["200"].content[
          "application/json"
        ].examples as Record<string, { value: unknown }>,
      )[0]!.value,
    );
  try {
    await withMcp(
      createTossinvestServer({
        apiBase: fake.url,
        env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "7" },
      }),
      async (client) => {
        for (const [name, args] of [
          ["list_accounts", {}],
          ["get_quotes", { symbols: "AAPL" }],
          ["get_holdings", {}],
          ["get_buying_power", { currency: "KRW" }],
          ["get_sellable_quantity", { symbol: "AAPL" }],
          ["list_orders", { status: "OPEN" }],
          ["get_orderbook", { symbol: "005930" }],
          ["get_trades", { symbol: "AAPL" }],
          ["get_price_limits", { symbol: "AAPL" }],
          ["get_candles", { symbol: "005930", interval: "1d" }],
          ["get_exchange_rate", { baseCurrency: "USD", quoteCurrency: "KRW" }],
          ["get_market_calendar", { market: "KR" }],
          ["get_market_calendar", { market: "US" }],
        ] as const)
          expect(
            (await tool(client, name, args)).result.isError,
          ).toBeUndefined();
      },
    );
    const received = new Set(
      fake.requests.map(
        (request) =>
          OPERATIONS.find((op) =>
            matchesOperation(op, request.method, request.path),
          )!.id,
      ),
    );
    expect(received).toEqual(allowed);
    expect(CURRENT_OPERATIONS).toEqual(received);
    expect(fake.seen("GET")).toHaveLength(14);
    expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
    expect(
      fake.seen("POST").filter((request) => request.path !== "/oauth2/token"),
    ).toHaveLength(0);
    expect(fake.seen("DELETE")).toHaveLength(0);
    expect(fake.requests).toHaveLength(15);
    for (const request of fake.seen("GET")) {
      expect(request.body).toBe("");
      expect(request.headers.get("authorization")).toBe(
        "Bearer fake-access-token-1",
      );
      expect(request.headers.get("x-tossinvest-account")).toBe(
        [
          "accounts",
          "prices",
          "stocks",
          "orderbook",
          "trades",
          "price-limits",
          "candles",
          "exchange-rate",
          "market-calendar/KR",
          "market-calendar/US",
        ].some((path) => request.path.endsWith(`/${path}`))
          ? null
          : "7",
      );
    }
    const token = fake.seen("POST")[0]!;
    expect(Object.fromEntries(new URLSearchParams(token.body))).toEqual({
      grant_type: "client_credentials",
      client_id: credentials.TOSSINVEST_CLIENT_ID,
      client_secret: credentials.TOSSINVEST_CLIENT_SECRET,
    });
  } finally {
    fake.stop();
  }
});

test("고정 fixture에서 생성한 계약은 재현 가능하다", async () => {
  const child = Bun.spawn(
    [
      process.execPath,
      `${import.meta.dir}/../scripts/generate-contract.ts`,
      "--check",
    ],
    { stdout: "pipe", stderr: "pipe" },
  );
  expect(await child.exited).toBe(0);
  expect(await new Response(child.stderr).text()).toBe("");
});

test("빌드한 production stdio bundle의 계좌·시세 도구가 실제 HTTP 목적지에 도달한다", async () => {
  const fake = new FakeToss(
    new Set([
      "issueOAuth2Token",
      "getSellableQuantity",
      "getOrderbook",
      "getTrades",
      "getPriceLimit",
      "getCandles",
    ]),
  );
  const directory = await mkdtemp(join(tmpdir(), "toss-rest-bundle-"));
  const preload = join(directory, "loopback.ts");
  const client = new Client({ name: "bundle-test", version: "1.0" });
  fake.on("GET", "/api/v1/sellable-quantity", {
    result: { sellableQuantity: "9007199254740993.001" },
  });
  const marketCalls = [
    {
      name: "get_orderbook",
      path: "/api/v1/orderbook",
      example: "krStock",
      args: { symbol: "005930" },
    },
    {
      name: "get_trades",
      path: "/api/v1/trades",
      example: "usStock",
      args: { symbol: "AAPL" },
    },
    {
      name: "get_price_limits",
      path: "/api/v1/price-limits",
      example: "usStock",
      args: { symbol: "AAPL" },
    },
    {
      name: "get_candles",
      path: "/api/v1/candles",
      example: "lastPage",
      args: { symbol: "005930", interval: "1d", adjusted: false },
    },
  ];
  for (const call of marketCalls)
    fake.on(
      "GET",
      call.path,
      canonical.paths[call.path].get.responses["200"].content[
        "application/json"
      ].examples[call.example].value,
    );
  try {
    await buildBundle(directory);
    // production base는 그대로 두고 시험 프로세스의 fetch 전송만 loopback으로 돌린다.
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
    const { result, body } = await tool(client, "get_sellable_quantity", {
      symbol: "AAPL",
    });
    expect(result.isError).toBeUndefined();
    expect(result.structuredContent).toEqual(body);
    expect(body).toEqual({
      result: { sellableQuantity: "9007199254740993.001" },
      metadata: {},
      sellable_quantity: "9007199254740993.001",
    });
    const listed = (await client.listTools()).tools;
    const manifest = JSON.parse(
      readFileSync(`${import.meta.dir}/../connector.json`, "utf8"),
    );
    expect(new Set(listed.map((item) => item.name))).toEqual(
      new Set(Object.keys(manifest.tools)),
    );
    for (const call of marketCalls) {
      const response = await tool(client, call.name, call.args);
      expect(response.result.isError).toBeUndefined();
      expect(response.result.structuredContent).toEqual(response.body);
      expect(response.body.result).toEqual(
        canonical.paths[call.path].get.responses["200"].content[
          "application/json"
        ].examples[call.example].value.result,
      );
      const received = fake.seen("GET", call.path);
      expect(received).toHaveLength(1);
      expect(received[0]!.body).toBe("");
      expect(received[0]!.headers.get("x-tossinvest-account")).toBeNull();
    }
    expect(fake.requests).toHaveLength(6);
    expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
    expect(
      fake.seen("POST").filter((request) => request.path !== "/oauth2/token"),
    ).toHaveLength(0);
    expect(fake.seen("DELETE")).toHaveLength(0);
    const request = fake.seen("GET")[0]!;
    expect(request.path).toBe("/api/v1/sellable-quantity");
    expect(request.query.get("symbol")).toBe("AAPL");
    expect(request.body).toBe("");
    expect(request.headers.get("x-tossinvest-account")).toBe(
      "9223372036854775807",
    );
  } finally {
    await client.close();
    fake.stop();
    await rm(directory, { recursive: true, force: true });
  }
});
