import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { createTossinvestServer } from "../src/server.ts";
import {
  FakeToss,
  credentials,
  expectFailure,
  tool,
  withMcp,
} from "./support.ts";

const canonical = JSON.parse(
  readFileSync(`${import.meta.dir}/fixtures/openapi-1.2.24.json`, "utf8"),
);
const cases = [
  [
    "/api/v1/exchange-rate",
    "get_exchange_rate",
    { baseCurrency: "USD", quoteCurrency: "KRW" },
  ],
  ["/api/v1/market-calendar/KR", "get_market_calendar", { market: "KR" }],
  ["/api/v1/market-calendar/US", "get_market_calendar", { market: "US" }],
] as const;
const allowed = new Set([
  "issueOAuth2Token",
  "getExchangeRate",
  "getKrMarketCalendar",
  "getUsMarketCalendar",
]);

for (const [path, name, args] of cases) {
  test(`${path} 404와 잘못된 429 본문을 안전한 오류로 바꾼다`, async () => {
    for (const status of [404, 429]) {
      const fake = new FakeToss(allowed);
      fake.on(
        "GET",
        path,
        status === 429
          ? { error: {} }
          : {
              error: {
                requestId: "synthetic",
                code: "exchange-rate-not-found",
                message: "민감한 원문",
              },
            },
        status,
      );
      try {
        await withMcp(
          createTossinvestServer({ apiBase: fake.url, env: credentials }),
          (client) =>
            expectFailure(
              client,
              status === 429
                ? "TOSSINVEST_RATE_LIMITED"
                : "TOSSINVEST_UNAVAILABLE",
              name,
              args,
            ),
        );
        expect(fake.seen("GET")).toHaveLength(status === 429 ? 2 : 1);
        expect(fake.seen("GET")[0]!.body).toBe("");
      } finally {
        fake.stop();
      }
    }
  });
  test(`${path} 필수 필드 누락·nullable 아닌 null·잘못된 타입은 전체를 거절한다`, async () => {
    const upstream = Object.values(
      canonical.paths[path].get.responses["200"].content["application/json"]
        .examples as Record<string, { value: any }>,
    )[0]!.value;
    for (const change of ["missing", "null", "type"] as const) {
      const response = structuredClone(upstream);
      const field = path.endsWith("exchange-rate") ? "rate" : "today";
      if (change === "missing") delete response.result[field];
      else response.result[field] = change === "null" ? null : 123;
      const fake = new FakeToss(allowed);
      fake.on("GET", path, response);
      try {
        await withMcp(
          createTossinvestServer({ apiBase: fake.url, env: credentials }),
          (client) =>
            expectFailure(client, "TOSSINVEST_UNAVAILABLE", name, args),
        );
      } finally {
        fake.stop();
      }
    }
  });
}

test("윤년의 실제 날짜와 UTC·음수 offset 시각을 그대로 HTTP query에 넣는다", async () => {
  const fake = new FakeToss(allowed);
  for (const [path] of cases)
    fake.on(
      "GET",
      path,
      Object.values(
        canonical.paths[path].get.responses["200"].content["application/json"]
          .examples as Record<string, { value: any }>,
      )[0]!.value,
    );
  try {
    await withMcp(
      createTossinvestServer({ apiBase: fake.url, env: credentials }),
      async (client) => {
        for (const dateTime of [
          "2024-02-29T23:59:59Z",
          "2024-02-29T23:59:59-05:00",
        ])
          expect(
            (
              await tool(client, "get_exchange_rate", {
                ...cases[0][2],
                dateTime,
              })
            ).result.isError,
          ).toBeUndefined();
        expect(
          (
            await tool(client, "get_market_calendar", {
              market: "US",
              date: "2024-02-29",
            })
          ).result.isError,
        ).toBeUndefined();
      },
    );
    expect(fake.seen("GET").map((r) => Object.fromEntries(r.query))).toEqual([
      { ...cases[0][2], dateTime: "2024-02-29T23:59:59Z" },
      { ...cases[0][2], dateTime: "2024-02-29T23:59:59-05:00" },
      { date: "2024-02-29" },
    ]);
  } finally {
    fake.stop();
  }
});
