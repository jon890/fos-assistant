import { expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import {
  CONTRACT_SHA256,
  OPERATIONS,
  matchesOperation,
} from "../src/api-contract.ts";
import { Tossinvest } from "../src/client.ts";
import { createTossinvestServer } from "../src/server.ts";
import { registerStockTools } from "../src/stock-tools.ts";
import { createToolRegistration } from "../src/tool-registration.ts";
import {
  FakeToss,
  credentials,
  expectFailure,
  json,
  tool,
  withMcp,
} from "./support.ts";

const raw = readFileSync(`${import.meta.dir}/fixtures/openapi-1.2.24.json`);
const canonical = JSON.parse(raw.toString());
const allowed = new Set([
  "issueOAuth2Token",
  "getStocks",
  "listStocks",
  "getStockWarnings",
]);
const paths = {
  get_stocks: "/api/v1/stocks",
  list_stocks: "/api/v1/stocks/all",
  get_stock_warnings: "/api/v1/stocks/{symbol}/warnings",
} as const;
type StockTool = keyof typeof paths;

function examples(name: StockTool): Record<string, { value: any }> {
  return canonical.paths[paths[name]].get.responses["200"].content[
    "application/json"
  ].examples;
}
const multiple = examples("get_stocks").multiple!.value;
const kospi = examples("list_stocks").kospi!.value;
const warnings = examples("get_stock_warnings").withWarnings!.value;
const listParameters = canonical.paths[paths.list_stocks].get.parameters;
const enumValues = (name: string): string[] =>
  listParameters.find((parameter: any) => parameter.name === name).schema.enum;

function moduleServer(client: Tossinvest) {
  const server = new McpServer({ name: "stock-module-test", version: "1.0" });
  registerStockTools(createToolRegistration(server), client);
  return server;
}
function setup() {
  const fake = new FakeToss(allowed);
  const client = new Tossinvest({
    apiBase: fake.url,
    env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "7" },
  });
  return { fake, client, server: moduleServer(client) };
}

/** 인증 POST와 금융 쓰기를 따로 센다. 종목 참조 조회에는 계좌 헤더나 body가 없다. */
function assertReads(fake: FakeToss, count: number) {
  expect(fake.seen("GET")).toHaveLength(count);
  expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
  expect(
    fake.requests.filter(
      (request) =>
        request.method === "DELETE" ||
        (request.method === "POST" && request.path !== "/oauth2/token"),
    ),
  ).toHaveLength(0);
  for (const request of fake.seen("GET")) {
    expect(request.body).toBe("");
    expect(request.headers.get("x-tossinvest-account")).toBeNull();
    expect(request.headers.get("content-type")).toBeNull();
  }
}

test("고정한 공식 1.2.24 fixture의 SHA와 입력 enum을 사용한다", async () => {
  expect(canonical.info.version).toBe("1.2.24");
  expect(createHash("sha256").update(raw).digest("hex")).toBe(CONTRACT_SHA256);
  const { fake, server } = setup();
  try {
    await withMcp(server, async (client) => {
      const listed = (await client.listTools()).tools;
      expect(listed.map((entry) => entry.name).sort()).toEqual([
        "get_stock_warnings",
        "get_stocks",
        "list_stocks",
      ]);
      for (const entry of listed)
        expect(entry.annotations?.readOnlyHint).toBe(true);
      const schema = listed.find((entry) => entry.name === "list_stocks")!
        .inputSchema as any;
      for (const name of ["market", "status", "securityType"])
        expect(schema.properties[name].enum).toEqual(enumValues(name));
      expect(schema.additionalProperties).toBe(false);
      expect(schema.required).toEqual(["market"]);
    });
    expect(fake.requests).toHaveLength(0);
  } finally {
    fake.stop();
  }
});

for (const name of Object.keys(paths) as StockTool[]) {
  for (const [key, example] of Object.entries(examples(name))) {
    test(`${name}의 공식 예시 ${key}를 SDK와 HTTP로 그대로 보존한다`, async () => {
      const { fake, server } = setup();
      const symbol = "005930";
      const path = paths[name].replace("{symbol}", symbol);
      const args =
        name === "get_stocks"
          ? {
              symbols: example.value.result
                .map((row: any) => row.symbol)
                .join(","),
            }
          : name === "list_stocks"
            ? { market: key === "nasdaq" ? "NASDAQ" : "KOSPI" }
            : { symbol };
      fake.on("GET", path, example.value);
      try {
        await withMcp(server, async (client) => {
          const { result, body } = await tool(client, name, args);
          expect(result.isError).toBeUndefined();
          expect(body).toEqual({ result: example.value.result, metadata: {} });
          expect(result.structuredContent).toEqual(body);
        });
        assertReads(fake, 1);
        expect(fake.seen("GET")[0]!.path).toBe(path);
      } finally {
        fake.stop();
      }
    });
  }
}

test("200개 상세의 모든 필드와 decimal, 중첩 null, boolean, 알 수 없는 enum을 보존한다", async () => {
  const { fake, server } = setup();
  // 공식 예시를 복제한 합성 경계 응답이다. schema를 순회해 성공 행을 만들지 않는다.
  const rows = Array.from({ length: 200 }, (_, index) => ({
    ...structuredClone(multiple.result[index % 2]),
    symbol: `S${index}`,
    name: "종목 이름".repeat(30),
    isCommonShare: index % 2 === 0,
    listDate: null,
    delistDate: index % 2 ? "2026-03-26" : null,
    sharesOutstanding: "9007199254740993.9876543210987",
    leverageFactor: "-0.000000000000000000000000001",
    koreanMarketDetail:
      index % 2
        ? null
        : {
            liquidationTrading: true,
            nxtSupported: false,
            krxTradingSuspended: true,
            nxtTradingSuspended: null,
          },
  }));
  Object.assign(rows[0]!, {
    market: "FUTURE_MARKET",
    securityType: "FUTURE_SECURITY",
    status: "FUTURE_STATUS",
    currency: "FUTURE_CURRENCY",
  });
  const symbols = rows.map((row) => row.symbol).join(",");
  fake.on("GET", paths.get_stocks, { result: rows });
  try {
    await withMcp(server, async (client) => {
      const { result, body } = await tool(client, "get_stocks", { symbols });
      expect(result.isError).toBeUndefined();
      expect(body).toEqual({ result: rows, metadata: {} });
      expect(result.structuredContent).toEqual(body);
    });
    assertReads(fake, 1);
    expect(Object.fromEntries(fake.seen("GET")[0]!.query)).toEqual({ symbols });
  } finally {
    fake.stop();
  }
});

test("상세 입력의 공백을 정리하고 점, 하이픈, 대소문자를 보존한다", async () => {
  const { fake, server } = setup();
  fake.on("GET", paths.get_stocks, { result: [] });
  try {
    await withMcp(server, async (client) => {
      const { result, body } = await tool(client, "get_stocks", {
        symbols: " 0101N0 , BRK.B, brk-b ",
      });
      expect(result.isError).toBeUndefined();
      expect(body.result).toEqual([]);
    });
    assertReads(fake, 1);
    expect(fake.seen("GET")[0]!.query.get("symbols")).toBe(
      "0101N0,BRK.B,brk-b",
    );
  } finally {
    fake.stop();
  }
});

for (const market of enumValues("market")) {
  test(`전체 목록 ${market}는 공식 필터를 생략해 한 번만 전송한다`, async () => {
    const { fake, server } = setup();
    fake.on("GET", paths.list_stocks, { result: [] });
    try {
      await withMcp(server, async (client) => {
        const { result, body } = await tool(client, "list_stocks", { market });
        expect(result.isError).toBeUndefined();
        expect(body).toEqual({ result: [], metadata: {} });
      });
      assertReads(fake, 1);
      expect(Object.fromEntries(fake.seen("GET")[0]!.query)).toEqual({
        market,
      });
    } finally {
      fake.stop();
    }
  });
}

for (const [index, securityType] of enumValues("securityType").entries()) {
  test(`전체 목록 ${securityType}와 상태, 보통주 필터를 함께 보낸다`, async () => {
    const { fake, server } = setup();
    const input = {
      market: enumValues("market")[index % 7]!,
      status: enumValues("status")[index % 3]!,
      securityType,
      commonShare: index % 2 === 0,
    };
    const rows = [
      { ...kospi.result[0], securityType, isCommonShare: input.commonShare },
    ];
    fake.on("GET", paths.list_stocks, { result: rows });
    try {
      await withMcp(server, async (client) => {
        const { result, body } = await tool(client, "list_stocks", input);
        expect(result.isError).toBeUndefined();
        expect(body).toEqual({ result: rows, metadata: {} });
      });
      assertReads(fake, 1);
      expect(Object.fromEntries(fake.seen("GET")[0]!.query)).toEqual({
        ...input,
        commonShare: String(input.commonShare),
      });
    } finally {
      fake.stop();
    }
  });
}

test("전체 목록 2800행을 잘라내거나 페이지를 만들지 않는다", async () => {
  const { fake, server } = setup();
  const rows = Array.from({ length: 2800 }, (_, index) => ({
    ...kospi.result[0],
    symbol: `S${String(index).padStart(4, "0")}`,
  }));
  fake.on("GET", paths.list_stocks, { result: rows });
  try {
    await withMcp(server, async (client) => {
      const { result, body } = await tool(client, "list_stocks", {
        market: "NASDAQ",
      });
      expect(result.isError).toBeUndefined();
      expect(body).toEqual({ result: rows, metadata: {} });
    });
    assertReads(fake, 1);
  } finally {
    fake.stop();
  }
});

for (const symbol of ["005930", "AAPL", "BRK.B", "brk-b"]) {
  test(`${symbol} 유의사항의 거래소와 null, 알 수 없는 코드를 보존한다`, async () => {
    const { fake, server } = setup();
    const rows = [
      ...warnings.result,
      {
        warningType: "VI_DYNAMIC",
        exchange: "NXT",
        startDate: null,
        endDate: null,
      },
      {
        warningType: "FUTURE_WARNING",
        exchange: null,
        startDate: null,
        endDate: null,
      },
    ];
    const path = `/api/v1/stocks/${symbol}/warnings`;
    fake.on("GET", path, { result: rows });
    try {
      await withMcp(server, async (client) => {
        const { result, body } = await tool(client, "get_stock_warnings", {
          symbol,
        });
        expect(result.isError).toBeUndefined();
        expect(body).toEqual({ result: rows, metadata: {} });
      });
      assertReads(fake, 1);
      expect(fake.seen("GET")[0]!.path).toBe(path);
      expect(fake.seen("GET")[0]!.query.size).toBe(0);
    } finally {
      fake.stop();
    }
  });
}

test("미국 종목의 빈 유의사항과 미존재 404를 구분한다", async () => {
  const { fake, server } = setup();
  fake.on("GET", "/api/v1/stocks/AAPL/warnings", { result: [] });
  fake.fail("GET", "/api/v1/stocks/MISSING/warnings", 404, "stock-not-found");
  try {
    await withMcp(server, async (client) => {
      const { result, body } = await tool(client, "get_stock_warnings", {
        symbol: "AAPL",
      });
      expect(result.isError).toBeUndefined();
      expect(body.result).toEqual([]);
      await expectFailure(
        client,
        "TOSSINVEST_INVALID_INPUT",
        "get_stock_warnings",
        { symbol: "MISSING" },
      );
    });
    assertReads(fake, 2);
  } finally {
    fake.stop();
  }
});

test("잘못된 enum, symbol, 경로 주입, 없는 페이지 인자는 인증도 전송하지 않는다", async () => {
  const { fake, server } = setup();
  const invalid: Array<[StockTool, Record<string, unknown>]> = [
    ["get_stocks", {}],
    ["get_stocks", { symbols: "" }],
    ["get_stocks", { symbols: ", ," }],
    [
      "get_stocks",
      { symbols: Array.from({ length: 201 }, () => "AAPL").join(",") },
    ],
    ["list_stocks", {}],
    ["list_stocks", { market: "KRX" }],
    ["list_stocks", { market: "KOSPI", status: "UNKNOWN" }],
    ["list_stocks", { market: "KOSPI", securityType: "UNKNOWN" }],
    ["list_stocks", { market: "KOSPI", commonShare: "false" }],
    ["list_stocks", { market: "KOSPI", cursor: "anything" }],
    ["list_stocks", { market: "KOSPI", limit: 20 }],
    ["get_stock_warnings", {}],
    ["get_stock_warnings", { symbol: "" }],
    ["get_stock_warnings", { symbol: "." }],
    ["get_stock_warnings", { symbol: ".." }],
    ["get_stock_warnings", { symbol: "005930,AAPL" }],
    ["get_stock_warnings", { symbol: " AAPL " }],
    ["get_stock_warnings", { symbol: "AAPL\n" }],
  ];
  for (const symbol of [
    "AAPL/other",
    "AAPL?account=7",
    "AAPL#fragment",
    "%2F",
    "AAPL\\other",
    "AAP\nL",
    "삼성전자",
  ]) {
    invalid.push(
      ["get_stocks", { symbols: symbol }],
      ["get_stock_warnings", { symbol }],
    );
  }
  try {
    await withMcp(server, async (client) => {
      for (const [name, arguments_] of invalid) {
        const result = await client.callTool({ name, arguments: arguments_ });
        expect(result.isError).toBe(true);
      }
    });
    expect(fake.requests).toHaveLength(0);
  } finally {
    fake.stop();
  }
});

test("페이지 없는 전체 목록이 1 MiB를 넘으면 명확한 오류로 끝낸다", async () => {
  const { fake, server } = setup();
  const rows = [{ ...kospi.result[0], name: "x".repeat(1024 * 1024 + 1) }];
  fake.routes.set(`GET ${paths.list_stocks}`, () => json({ result: rows }));
  try {
    await withMcp(server, (client) =>
      expectFailure(client, "TOSSINVEST_UNAVAILABLE", "list_stocks", {
        market: "NASDAQ",
      }),
    );
    assertReads(fake, 1);
  } finally {
    fake.stop();
  }
});

test("세 도구의 HTTP operation을 구별하고 인증과 금융 전송을 따로 센다", async () => {
  const { fake, server } = setup();
  fake.on("GET", paths.get_stocks, multiple);
  fake.on("GET", paths.list_stocks, kospi);
  fake.on("GET", "/api/v1/stocks/005930/warnings", warnings);
  try {
    await withMcp(server, async (client) => {
      for (const [name, input] of [
        ["get_stocks", { symbols: "005930,AAPL" }],
        ["list_stocks", { market: "KOSPI", commonShare: false }],
        ["get_stock_warnings", { symbol: "005930" }],
      ] as const)
        expect(
          (await tool(client, name, input)).result.isError,
        ).toBeUndefined();
    });
    assertReads(fake, 3);
    expect(fake.requests).toHaveLength(4);
    expect(
      new Set(
        fake.requests.map(
          (request) =>
            OPERATIONS.find((op) =>
              matchesOperation(op, request.method, request.path),
            )!.id,
        ),
      ),
    ).toEqual(allowed);
  } finally {
    fake.stop();
  }
});

test("금융 POST와 DELETE를 허용 목록 위반으로 확인하고 대역을 반드시 종료한다", async () => {
  for (const [method, path] of [
    ["POST", "/api/v1/orders"],
    ["DELETE", "/api/v1/conditional-orders/id"],
  ] as const) {
    const fake = new FakeToss(allowed);
    fake.on(method, path, {});
    try {
      await fetch(`${fake.url}${path}`, { method });
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(0);
      expect(fake.seen(method, path)).toHaveLength(1);
    } finally {
      expect(() => fake.stop()).toThrow();
    }
  }
});

test("서로 다른 client도 STOCK_ALL 1회/초를 공유하고 STOCK은 별도로 진행한다", async () => {
  const { fake, client, server } = setup();
  const other = new Tossinvest({ apiBase: fake.url, env: credentials });
  const otherServer = moduleServer(other);
  const arrivals: Array<{ path: string; at: number }> = [];
  for (const path of [paths.get_stocks, paths.list_stocks])
    fake.routes.set(`GET ${path}`, () => {
      arrivals.push({ path, at: Date.now() });
      return json({ result: [] });
    });
  try {
    // 토큰과 큐 예산을 client 인스턴스 밖에서 공유하는 실제 정의를 사용한다.
    await client.token();
    await withMcp(server, (sdk) =>
      withMcp(otherServer, async (otherSdk) => {
        await tool(sdk, "list_stocks", { market: "KOSPI" });
        const [delayed, independent] = await Promise.all([
          tool(otherSdk, "list_stocks", { market: "NASDAQ" }),
          tool(sdk, "get_stocks", { symbols: "AAPL" }),
        ]);
        expect(delayed.result.isError).toBeUndefined();
        expect(independent.result.isError).toBeUndefined();
      }),
    );
    expect(arrivals.map((arrival) => arrival.path)).toEqual([
      paths.list_stocks,
      paths.get_stocks,
      paths.list_stocks,
    ]);
    expect(arrivals[2]!.at - arrivals[0]!.at).toBeGreaterThanOrEqual(1000);
    assertReads(fake, 3);
  } finally {
    fake.stop();
  }
});

test("기본 4초 deadline은 STOCK_ALL 큐 대기도 포함해 늦은 HTTP를 보내지 않는다", async () => {
  const { fake, client, server } = setup();
  fake.on("GET", paths.list_stocks, { result: [] });
  try {
    await client.token();
    await withMcp(server, async (sdk) => {
      const results = await Promise.all(
        Array.from({ length: 5 }, () =>
          tool(sdk, "list_stocks", { market: "KOSPI" }),
        ),
      );
      expect(results.slice(0, 4).every((entry) => !entry.result.isError)).toBe(
        true,
      );
      expect(results[4]!.result.isError).toBe(true);
      expect(results[4]!.body).toEqual({
        error: { code: "TOSSINVEST_NETWORK" },
      });
    });
    assertReads(fake, 4);
  } finally {
    fake.stop();
  }
});

test("production factory가 기존 도구와 종목 도구를 함께 등록한다", async () => {
  const fake = new FakeToss(allowed);
  const options = { apiBase: fake.url, env: credentials };
  const server = createTossinvestServer(options);
  try {
    await withMcp(server, async (sdk) => {
      const listed = (await sdk.listTools()).tools.map((entry) => entry.name);
      expect(listed.sort()).toEqual(
        [
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
          "prepare_trade",
          "create_order",
          "cancel_order",
        ].sort(),
      );
    });
    expect(fake.requests).toHaveLength(0);
  } finally {
    fake.stop();
  }
});
