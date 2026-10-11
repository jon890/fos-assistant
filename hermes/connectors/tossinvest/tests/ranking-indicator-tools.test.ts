import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { Tossinvest } from "../src/client.ts";
import { operationFor } from "../src/api-contract.ts";
import { createToolRegistration } from "../src/tool-registration.ts";
import { registerRankingTools } from "../src/ranking-tools.ts";
import { registerIndicatorTools } from "../src/indicator-tools.ts";
import {
  FakeToss,
  credentials,
  json,
  tool,
  withMcp,
  expectFailure,
} from "./support.ts";

const fixture = JSON.parse(
  readFileSync(`${import.meta.dir}/fixtures/openapi-1.2.24.json`, "utf8"),
);
const allowed = new Set([
  "issueOAuth2Token",
  "getRankings",
  "getMarketIndicatorPrices",
  "getMarketIndicatorCandles",
  "getMarketIndicatorInvestorTrading",
]);
const paths = {
  get_rankings: "/api/v1/rankings",
  get_market_indicator_prices: "/api/v1/market-indicators/prices",
  get_market_indicator_candles: "/api/v1/market-indicators/KOSPI/candles",
  get_market_indicator_investor_trading:
    "/api/v1/market-indicators/KOSPI/investor-trading",
};
const inputs = {
  get_rankings: {
    type: "MARKET_TRADING_AMOUNT",
    marketCountry: "KR",
    duration: "1d",
  },
  get_market_indicator_prices: { symbols: "KOSPI,KOSDAQ" },
  get_market_indicator_candles: { symbol: "KOSPI", interval: "1d" },
  get_market_indicator_investor_trading: { symbol: "KOSPI", interval: "1d" },
};
type ToolName = keyof typeof paths;
function examples(name: ToolName): any[] {
  const path = paths[name].replace("/KOSPI/", "/{symbol}/");
  return Object.values(
    fixture.paths[path].get.responses["200"].content["application/json"]
      .examples,
  ).map((item: any) => item.value);
}
function server(fake: FakeToss, timeoutMs?: number) {
  const server = new McpServer({
    name: "ranking-indicator-test",
    version: "1.0.0",
  });
  const client = new Tossinvest({
    apiBase: fake.url,
    env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "123" },
    timeoutMs,
  });
  const register = createToolRegistration(server);
  registerRankingTools(register, client);
  registerIndicatorTools(register, client);
  return server;
}
function readOnlyRequests(fake: FakeToss, count: number) {
  expect(fake.seen("GET")).toHaveLength(count);
  expect(fake.seen("POST", "/oauth2/token")).toHaveLength(count ? 1 : 0);
  expect(
    fake.requests.filter(
      (r) =>
        r.method === "DELETE" ||
        (r.method === "POST" && r.path !== "/oauth2/token"),
    ),
  ).toHaveLength(0);
  for (const r of fake.seen("GET")) {
    expect(r.body).toBe("");
    expect(r.headers.get("x-tossinvest-account")).toBeNull();
    expect(r.headers.get("authorization")).toMatch(
      /^Bearer fake-access-token-/,
    );
  }
}

for (const name of Object.keys(paths) as ToolName[]) {
  test(`${name}: 공식 성공 예시의 전체 필드를 SDK와 HTTP에서 보존한다`, async () => {
    const fake = new FakeToss(allowed);
    const values = examples(name);
    fake.sequence(
      "GET",
      paths[name],
      values.map((value) => () => json(value)),
    );
    try {
      await withMcp(server(fake), async (client) => {
        for (const value of values) {
          const { body, result } = await tool(client, name, inputs[name]);
          expect(result.isError).not.toBe(true);
          expect(body.result).toEqual(value.result);
          expect(body.metadata).toEqual({});
          expect(result.structuredContent).toEqual(body);
          if (name === "get_market_indicator_candles")
            expect(body.nextBefore).toBe(value.result.nextBefore);
          if (name === "get_market_indicator_investor_trading")
            expect(body.nextUntil).toBe(value.result.nextUntil);
        }
      });
      readOnlyRequests(fake, values.length);
      expect(
        new Set(fake.seen("GET").map((r) => operationFor(r.method, r.path).id)),
      ).toEqual(new Set([operationFor("GET", paths[name]).id]));
    } finally {
      fake.stop();
    }
  });
}

test("긴 decimal·null을 보존하고 추가 키를 제거하며 잘못된 행·JSON·큰 본문은 전체 실패한다", async () => {
  const fake = new FakeToss(allowed);
  const rows = structuredClone(
    examples("get_market_indicator_prices")[0].result,
  );
  rows[0].lastPrice = "-0.888888888888888888888888887";
  rows[0].timestamp = null;
  rows[0].extra = { provisional: false, unknown: null };
  fake.sequence("GET", paths.get_market_indicator_prices, [
    () => json({ result: rows }),
    () => json({ result: [rows[0], { ...rows[1], lastPrice: "bad" }] }),
    () => new Response("{invalid-json"),
    () => new Response("x".repeat(1024 * 1024 + 1)),
  ]);
  try {
    await withMcp(server(fake), async (client) => {
      const { body } = await tool(
        client,
        "get_market_indicator_prices",
        inputs.get_market_indicator_prices,
      );
      expect(body.result).toEqual(
        rows.map(({ extra: _extra, ...official }: any) => official),
      );
      for (let i = 0; i < 3; i++)
        await expectFailure(
          client,
          "TOSSINVEST_UNAVAILABLE",
          "get_market_indicator_prices",
          inputs.get_market_indicator_prices,
        );
    });
    readOnlyRequests(fake, 4);
  } finally {
    fake.stop();
  }
});

test("기본 count와 excludeInvestmentCaution, 선택 query 생략을 공식 값으로 보낸다", async () => {
  const fake = new FakeToss(allowed);
  for (const name of Object.keys(paths) as ToolName[])
    fake.on("GET", paths[name], examples(name)[0]);
  try {
    await withMcp(server(fake), async (client) => {
      for (const name of Object.keys(paths) as ToolName[])
        await tool(client, name, inputs[name]);
      const listed = await client.listTools();
      expect(listed.tools.map((item) => item.name).sort()).toEqual(
        Object.keys(paths).sort(),
      );
      expect(
        listed.tools.every((item) => item.annotations?.readOnlyHint === true),
      ).toBe(true);
    });
    expect(
      Object.fromEntries(fake.seen("GET", paths.get_rankings)[0]!.query),
    ).toEqual({
      ...inputs.get_rankings,
      excludeInvestmentCaution: "false",
      count: "100",
    });
    expect(
      Object.fromEntries(
        fake.seen("GET", paths.get_market_indicator_candles)[0]!.query,
      ),
    ).toEqual({ interval: "1d", count: "100" });
    expect(
      Object.fromEntries(
        fake.seen("GET", paths.get_market_indicator_investor_trading)[0]!.query,
      ),
    ).toEqual({ interval: "1d", count: "10" });
    readOnlyRequests(fake, 4);
    expect(
      new Set(fake.seen("GET").map((r) => operationFor(r.method, r.path).id)),
    ).toEqual(
      new Set([
        "getRankings",
        "getMarketIndicatorPrices",
        "getMarketIndicatorCandles",
        "getMarketIndicatorInvestorTrading",
      ]),
    );
  } finally {
    fake.stop();
  }
});

const types = [
  "MARKET_TRADING_AMOUNT",
  "MARKET_TRADING_VOLUME",
  "TOP_GAINERS",
  "TOP_LOSERS",
  "TOSS_SECURITIES_TRADING_AMOUNT",
  "TOSS_SECURITIES_TRADING_VOLUME",
];
const durations = ["realtime", "1d", "1w", "1mo", "3mo", "6mo", "1y"];
for (const type of types)
  for (const duration of durations)
    for (const marketCountry of ["KR", "US"]) {
      test(`랭킹 조합 ${type}/${duration}/${marketCountry}`, async () => {
        const fake = new FakeToss(allowed);
        fake.on("GET", paths.get_rankings, examples("get_rankings")[3]);
        const invalid =
          duration === "realtime" &&
          ["TOP_GAINERS", "TOP_LOSERS"].includes(type);
        try {
          await withMcp(server(fake), async (client) => {
            const args = {
              type,
              duration,
              marketCountry,
              excludeInvestmentCaution: false,
              count: marketCountry === "KR" ? 1 : 100,
            };
            if (invalid)
              await expectFailure(
                client,
                "TOSSINVEST_INVALID_INPUT",
                "get_rankings",
                args,
              );
            else {
              const { body } = await tool(client, "get_rankings", args);
              expect(body.result).toEqual(examples("get_rankings")[3].result);
              expect(Object.fromEntries(fake.seen("GET")[0]!.query)).toEqual({
                type,
                duration,
                marketCountry,
                excludeInvestmentCaution: "false",
                count: String(args.count),
              });
            }
          });
          readOnlyRequests(fake, invalid ? 0 : 1);
        } finally {
          fake.stop();
        }
      });
    }

test("잘못된 enum·경계·날짜·추가 입력은 인증을 포함해 HTTP 전에 거절한다", async () => {
  const fake = new FakeToss(allowed);
  const invalid: Array<[ToolName, Record<string, unknown>]> = [
    ["get_rankings", { type: "UNKNOWN" }],
    ["get_rankings", { marketCountry: "JP" }],
    ["get_rankings", { duration: "2d" }],
    ["get_rankings", { count: 0 }],
    ["get_rankings", { count: 101 }],
    ["get_rankings", { count: 1.5 }],
    ["get_rankings", { excludeInvestmentCaution: "false" }],
    ["get_market_indicator_prices", { symbols: "" }],
    ["get_market_indicator_prices", { symbols: "KOSPI," }],
    ["get_market_indicator_prices", { symbols: "KOSPI,AAPL" }],
    [
      "get_market_indicator_prices",
      { symbols: Array(201).fill("KOSPI").join(",") },
    ],
    ["get_market_indicator_candles", { symbol: "KOSPI/other" }],
    ["get_market_indicator_candles", { symbol: "KR_BOND_2Y", interval: "1m" }],
    ["get_market_indicator_candles", { interval: "1w" }],
    ["get_market_indicator_candles", { count: 201 }],
    ["get_market_indicator_candles", { count: 0 }],
    ["get_market_indicator_candles", { before: "2026-02-30T09:00:00+09:00" }],
    ["get_market_indicator_candles", { adjusted: true }],
    ["get_market_indicator_investor_trading", { symbol: "KR_BOND_10Y" }],
    ["get_market_indicator_investor_trading", { interval: "1m" }],
    ["get_market_indicator_investor_trading", { count: 101 }],
    ["get_market_indicator_investor_trading", { count: 0 }],
    ["get_market_indicator_investor_trading", { until: "2026-02-30" }],
  ];
  try {
    await withMcp(server(fake), async (client) => {
      for (const [name, extra] of invalid)
        expect(
          (
            await client.callTool({
              name,
              arguments: { ...inputs[name], ...extra },
            })
          ).isError,
        ).toBe(true);
      for (const name of Object.keys(paths) as ToolName[])
        for (const key of ["account", "url", "token"])
          expect(
            (
              await client.callTool({
                name,
                arguments: { ...inputs[name], [key]: "invalid" },
              })
            ).isError,
          ).toBe(true);
    });
    expect(fake.requests).toHaveLength(0);
  } finally {
    fake.stop();
  }
});

test("8개 지표·200개 시세와 캔들 및 투자자 페이지를 정확한 query로 요청한다", async () => {
  const fake = new FakeToss(allowed);
  const catalog = [
    "KOSPI",
    "KOSDAQ",
    "KR_BOND_2Y",
    "KR_BOND_3Y",
    "KR_BOND_5Y",
    "KR_BOND_10Y",
    "KR_BOND_20Y",
    "KR_BOND_30Y",
  ];
  const candles = examples("get_market_indicator_candles");
  const trading = examples("get_market_indicator_investor_trading");
  fake.on(
    "GET",
    paths.get_market_indicator_prices,
    examples("get_market_indicator_prices")[1],
  );
  for (const symbol of catalog)
    fake.on("GET", `/api/v1/market-indicators/${symbol}/candles`, candles[1]);
  fake.sequence(
    "GET",
    paths.get_market_indicator_candles,
    candles.map((value) => () => json(value)),
  );
  for (const symbol of ["KOSPI", "KOSDAQ"])
    fake.sequence(
      "GET",
      `/api/v1/market-indicators/${symbol}/investor-trading`,
      trading.map((value) => () => json(value)),
    );
  try {
    await withMcp(server(fake), async (client) => {
      await tool(client, "get_market_indicator_prices", {
        symbols: catalog.join(","),
      });
      await tool(client, "get_market_indicator_prices", {
        symbols: Array(200).fill("KOSPI").join(","),
      });
      for (const symbol of catalog) {
        const first = await tool(client, "get_market_indicator_candles", {
          symbol,
          interval: "1d",
          count: 1,
        });
        if (symbol === "KOSPI") {
          const second = await tool(client, "get_market_indicator_candles", {
            symbol,
            interval: "1m",
            count: 200,
            before: first.body.nextBefore,
          });
          expect(second.body.nextBefore).toBeNull();
          expect(
            Object.fromEntries(
              fake.seen("GET", paths.get_market_indicator_candles)[1]!.query,
            ),
          ).toEqual({
            interval: "1m",
            count: "200",
            before: candles[0].result.nextBefore,
          });
        }
      }
      for (const symbol of ["KOSPI", "KOSDAQ"])
        for (const interval of ["1d", "1w", "1mo", "1y"]) {
          const result = await tool(
            client,
            "get_market_indicator_investor_trading",
            {
              symbol,
              interval,
              count: interval === "1d" ? 1 : 100,
              ...(interval === "1w"
                ? { until: trading[0].result.nextUntil }
                : {}),
            },
          );
          expect(result.body.result).toEqual(
            interval === "1d" ? trading[0].result : trading[1].result,
          );
        }
      expect(
        fake
          .seen("GET", paths.get_market_indicator_investor_trading)[1]!
          .query.get("until"),
      ).toBe(trading[0].result.nextUntil);
    });
    readOnlyRequests(fake, 19);
    expect(
      new Set(fake.seen("GET").map((r) => operationFor(r.method, r.path).id)),
    ).toEqual(
      new Set([
        "getMarketIndicatorPrices",
        "getMarketIndicatorCandles",
        "getMarketIndicatorInvestorTrading",
      ]),
    );
  } finally {
    fake.stop();
  }
});

for (const name of Object.keys(paths) as ToolName[]) {
  test(`${name}: 잘못된 행은 전체 실패하고 상위 서비스 오류와 timeout은 마스킹한다`, async () => {
    const fake = new FakeToss(allowed);
    try {
      await withMcp(server(fake, 80), async (client) => {
        fake.on("GET", paths[name], { result: [{ invalid: true }] });
        await expectFailure(
          client,
          "TOSSINVEST_UNAVAILABLE",
          name,
          inputs[name],
        );
      });
    } finally {
      fake.stop();
    }
    for (const [status, code, reason] of [
      [400, "unsupported-symbol", "TOSSINVEST_INVALID_INPUT"],
      [400, "invalid-request", "TOSSINVEST_INVALID_INPUT"],
      [429, "rate-limit-exceeded", "TOSSINVEST_RATE_LIMITED"],
      [500, "internal-error", "TOSSINVEST_UNAVAILABLE"],
    ] as const) {
      const upstream = new FakeToss(allowed);
      upstream.fail("GET", paths[name], status, code);
      try {
        await withMcp(server(upstream), (client) =>
          expectFailure(client, reason, name, inputs[name]),
        );
        readOnlyRequests(upstream, status === 429 ? 2 : 1);
      } finally {
        upstream.stop();
      }
    }
    const slow = new FakeToss(allowed);
    slow.routes.set(`GET ${paths[name]}`, async () => {
      await Bun.sleep(100);
      return json(examples(name)[0]);
    });
    try {
      await withMcp(server(slow, 40), (client) =>
        expectFailure(client, "TOSSINVEST_NETWORK", name, inputs[name]),
      );
    } finally {
      slow.stop();
    }
  });
}
