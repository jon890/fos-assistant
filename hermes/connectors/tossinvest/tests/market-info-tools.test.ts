import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { createTossinvestServer } from "../src/server.ts";
import {
  FakeToss,
  credentials,
  expectFailure,
  json,
  tool,
  withMcp,
} from "./support.ts";

const canonical = JSON.parse(
  readFileSync(`${import.meta.dir}/fixtures/openapi-1.2.24.json`, "utf8"),
);
const exchangePath = "/api/v1/exchange-rate";
const krPath = "/api/v1/market-calendar/KR";
const usPath = "/api/v1/market-calendar/US";
const examples = (path: string): Record<string, { value: any }> =>
  canonical.paths[path].get.responses["200"].content["application/json"]
    .examples;
const allowed = new Set([
  "issueOAuth2Token",
  "getExchangeRate",
  "getKrMarketCalendar",
  "getUsMarketCalendar",
]);

function moduleServer(fake: FakeToss, timeoutMs = 4000) {
  return createTossinvestServer({
    apiBase: fake.url,
    timeoutMs,
    env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "7" },
  });
}

/** 인증 이외 POST/DELETE와 대상 밖 GET은 finally의 stop에서도 거절한다. */
function assertReadRequests(fake: FakeToss) {
  expect(fake.seen("DELETE")).toHaveLength(0);
  expect(fake.seen("POST").every((r) => r.path === "/oauth2/token")).toBe(true);
  for (const request of fake.seen("GET")) {
    expect([exchangePath, krPath, usPath]).toContain(request.path);
    expect(request.body).toBe("");
    expect(request.headers.get("x-tossinvest-account")).toBeNull();
    expect(request.headers.get("x-tossinvest-group")).toBeNull();
    expect(
      [...request.headers.keys()].some((name) => /group/i.test(name)),
    ).toBe(false);
  }
}

for (const [path, name, args] of [
  [
    exchangePath,
    "get_exchange_rate",
    { baseCurrency: "USD", quoteCurrency: "KRW" },
  ],
  [krPath, "get_market_calendar", { market: "KR" }],
  [usPath, "get_market_calendar", { market: "US" }],
] as const) {
  for (const [exampleName, example] of Object.entries(examples(path))) {
    test(`공식 ${exampleName} ${path}를 SDK와 HTTP로 보존한다`, async () => {
      const fake = new FakeToss(allowed);
      fake.on("GET", path, {
        ...example.value,
        metadata: { upstream: "응답 확장 필드를 metadata로 노출하지 않는다" },
      });
      try {
        await withMcp(moduleServer(fake), async (client) => {
          const listed = await client.listTools();
          expect(listed.tools.map((t) => t.name).sort()).toEqual([
            "get_buying_power",
            "get_exchange_rate",
            "get_holdings",
            "get_market_calendar",
            "get_quotes",
            "get_sellable_quantity",
            "list_accounts",
            "list_orders",
          ]);
          expect(listed.tools.every((t) => t.annotations?.readOnlyHint)).toBe(
            true,
          );
          const { result, body } = await tool(client, name, args);
          expect(result.isError).toBeUndefined();
          expect(body).toEqual({ result: example.value.result, metadata: {} });
          expect(result.structuredContent).toEqual(body);
        });
        expect(fake.seen("GET")).toHaveLength(1);
        expect(fake.seen("POST")).toHaveLength(1);
        expect(
          fake
            .seen("GET")[0]!
            .query.has(path === exchangePath ? "dateTime" : "date"),
        ).toBe(false);
        assertReadRequests(fake);
      } finally {
        fake.stop();
      }
    });
  }
}

test("KRW/USD 양방향과 offset 인코딩, 양 시장의 지정 날짜를 보존한다", async () => {
  const fake = new FakeToss(allowed);
  // 공식 USD→KRW 예시를 바탕으로 역방향 decimal 정밀도를 명시한 합성 응답이다.
  const reverse = {
    result: {
      ...examples(exchangePath).usdToKrwUp!.value.result,
      baseCurrency: "KRW",
      quoteCurrency: "USD",
      rate: "0.000724375226367258",
      midRate: "0.000727272727272727",
      basisPoint: "-39.842",
      rateChangeType: "DOWN",
    },
  };
  fake.on("GET", exchangePath, reverse);
  fake.on("GET", krPath, examples(krPath).businessDay!.value);
  fake.on("GET", usPath, examples(usPath).businessDay!.value);
  const dateTime = "2026-03-25T09:30:00+09:00";
  try {
    await withMcp(moduleServer(fake), async (client) => {
      expect(
        (
          await tool(client, "get_exchange_rate", {
            baseCurrency: "KRW",
            quoteCurrency: "USD",
            dateTime,
          })
        ).body.result,
      ).toEqual(reverse.result);
      for (const market of ["KR", "US"])
        expect(
          (
            await tool(client, "get_market_calendar", {
              market,
              date: "2026-03-25",
            })
          ).result.isError,
        ).toBeUndefined();
    });
    expect(fake.seen("GET").map((r) => r.path)).toEqual([
      exchangePath,
      krPath,
      usPath,
    ]);
    expect(fake.requests).toHaveLength(4);
    const exchange = fake.seen("GET")[0]!;
    expect(Object.fromEntries(exchange.query)).toEqual({
      baseCurrency: "KRW",
      quoteCurrency: "USD",
      dateTime,
    });
    expect(exchange.query.toString()).toContain("%2B09%3A00");
    for (const request of fake.seen("GET").slice(1))
      expect(Object.fromEntries(request.query)).toEqual({ date: "2026-03-25" });
    assertReadRequests(fake);
  } finally {
    fake.stop();
  }
});

test("KR의 null 단일가와 미국 서머타임 전후 시각을 계산 없이 돌려준다", async () => {
  const kr = structuredClone(examples(krPath).businessDay!.value);
  kr.result.today.integrated.regularMarket.singlePriceAuctionStartTime = null;
  const winter = structuredClone(examples(usPath).businessDay!.value);
  // 공식 세션 schema의 필드를 명시한 겨울 시간 합성 응답이다.
  winter.result.today = {
    date: "2026-01-07",
    dayMarket: null,
    preMarket: {
      startTime: "2026-01-07T18:00:00+09:00",
      endTime: "2026-01-07T23:30:00+09:00",
    },
    regularMarket: {
      startTime: "2026-01-07T23:30:00+09:00",
      endTime: "2026-01-08T06:00:00+09:00",
    },
    afterMarket: {
      startTime: "2026-01-08T06:00:00+09:00",
      endTime: "2026-01-08T08:00:00+09:00",
    },
  };
  const fake = new FakeToss(allowed);
  fake.on("GET", krPath, kr);
  fake.on("GET", usPath, winter);
  try {
    await withMcp(moduleServer(fake), async (client) => {
      expect(
        (await tool(client, "get_market_calendar", { market: "KR" })).body
          .result,
      ).toEqual(kr.result);
      expect(
        (
          await tool(client, "get_market_calendar", {
            market: "US",
            date: "2026-01-07",
          })
        ).body.result,
      ).toEqual(winter.result);
    });
    assertReadRequests(fake);
  } finally {
    fake.stop();
  }
});

test("잘못된 날짜·시각·통화·시장과 추가 인자는 인증도 HTTP도 보내지 않는다", async () => {
  const fake = new FakeToss(allowed);
  try {
    await withMcp(moduleServer(fake), async (client) => {
      const currency = { baseCurrency: "USD", quoteCurrency: "KRW" };
      for (const args of [
        {},
        { ...currency, baseCurrency: "EUR" },
        { ...currency, quoteCurrency: "usd" },
        { ...currency, baseCurrency: "KRW" },
        ...[
          "2026-02-30T09:30:00+09:00",
          "2026-03-25T09:30:00",
          "2026-03-25T09:30+09:00",
          "2026-03-25T09:30Z",
          "2026-03-25T25:00:00Z",
          "",
        ].map((dateTime) => ({ ...currency, dateTime })),
        { ...currency, account: "7" },
        { ...currency, url: "http://127.0.0.1" },
      ]) {
        const result = await client.callTool({
          name: "get_exchange_rate",
          arguments: args,
        });
        expect(result.isError).toBe(true);
      }
      for (const args of [
        {},
        { market: "JP" },
        { market: "kr" },
        ...["2026-02-29", "2026-04-31", "2026-13-01", "2026-3-25", ""].map(
          (date) => ({ market: "KR", date }),
        ),
        { market: "US", path: "/api/v1/orders" },
      ]) {
        const result = await client.callTool({
          name: "get_market_calendar",
          arguments: args,
        });
        expect(result.isError).toBe(true);
      }
    });
    expect(fake.requests).toHaveLength(0);
  } finally {
    fake.stop();
  }
});

test("환율과 양 시장 캘린더는 서로 다른 client에서도 MARKET_INFO 큐를 공유한다", async () => {
  const fake = new FakeToss(allowed);
  const arrivals: number[] = [];
  for (const path of [exchangePath, krPath, usPath])
    fake.routes.set(`GET ${path}`, () => {
      arrivals.push(Date.now());
      return json(Object.values(examples(path))[0]!.value);
    });
  try {
    await withMcp(moduleServer(fake), async (a) => {
      await withMcp(moduleServer(fake), async (b) => {
        const results = await Promise.all([
          tool(a, "get_exchange_rate", {
            baseCurrency: "USD",
            quoteCurrency: "KRW",
          }),
          tool(b, "get_market_calendar", { market: "KR" }),
          tool(b, "get_market_calendar", { market: "US" }),
        ]);
        expect(results.every((r) => !r.result.isError)).toBe(true);
      });
    });
    expect(arrivals).toHaveLength(3);
    expect(arrivals[1]! - arrivals[0]!).toBeGreaterThanOrEqual(334);
    expect(arrivals[2]! - arrivals[1]!).toBeGreaterThanOrEqual(334);
    expect(fake.seen("POST")).toHaveLength(1);
    assertReadRequests(fake);
  } finally {
    fake.stop();
  }
});

for (const [path, name, args] of [
  [
    exchangePath,
    "get_exchange_rate",
    { baseCurrency: "USD", quoteCurrency: "KRW" },
  ],
  [krPath, "get_market_calendar", { market: "KR" }],
  [usPath, "get_market_calendar", { market: "US" }],
] as const) {
  for (const [status, code] of [
    [429, "TOSSINVEST_RATE_LIMITED"],
    [500, "TOSSINVEST_UNAVAILABLE"],
    [503, "TOSSINVEST_UNAVAILABLE"],
  ] as const) {
    test(`${path} HTTP ${status}는 기존 client의 재시도 상한과 안전한 오류를 지킨다`, async () => {
      const fake = new FakeToss(allowed);
      fake.fail(
        "GET",
        path,
        status,
        status === 429 ? "rate-limit-exceeded" : "internal-error",
      );
      try {
        await withMcp(moduleServer(fake), (client) =>
          expectFailure(client, code, name, args),
        );
        expect(fake.seen("GET")).toHaveLength(status === 429 ? 2 : 1);
        assertReadRequests(fake);
      } finally {
        fake.stop();
      }
    });
  }
  test(`${path} 잘못된 성공 응답과 timeout을 거절한다`, async () => {
    for (const slow of [false, true]) {
      const fake = new FakeToss(allowed);
      fake.routes.set(`GET ${path}`, async () => {
        if (slow) await Bun.sleep(250);
        return json({ result: {} });
      });
      try {
        await withMcp(moduleServer(fake, slow ? 100 : 4000), (client) =>
          expectFailure(
            client,
            slow ? "TOSSINVEST_NETWORK" : "TOSSINVEST_UNAVAILABLE",
            name,
            args,
          ),
        );
        expect(fake.seen("GET")).toHaveLength(1);
        assertReadRequests(fake);
      } finally {
        fake.stop();
      }
    }
  });
}

test("429 이후 공유 큐의 deadline 만료는 다른 시장 HTTP를 보내지 않는다", async () => {
  const fake = new FakeToss(allowed);
  fake.fail("GET", exchangePath, 429, "rate-limit-exceeded");
  try {
    await withMcp(moduleServer(fake, 200), async (client) => {
      await expectFailure(client, "TOSSINVEST_NETWORK", "get_exchange_rate", {
        baseCurrency: "USD",
        quoteCurrency: "KRW",
      });
      await expectFailure(client, "TOSSINVEST_NETWORK", "get_market_calendar", {
        market: "US",
      });
    });
    expect(fake.seen("GET")).toHaveLength(1);
    expect(fake.seen("GET", usPath)).toHaveLength(0);
    assertReadRequests(fake);
  } finally {
    fake.stop();
  }
});
