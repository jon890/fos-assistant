import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { createTossinvestServer } from "../src/server.ts";
import { OPERATIONS, matchesOperation } from "../src/api-contract.ts";
import { RESPONSE_MAX_BYTES } from "../src/constants.ts";
import {
  FakeToss,
  credentials,
  json,
  apiError,
  tool,
  expectFailure,
  withMcp,
} from "./support.ts";

const canonical = JSON.parse(
  readFileSync(`${import.meta.dir}/fixtures/openapi-1.2.24.json`, "utf8"),
);
const tools = [
  { name: "get_orderbook", id: "getOrderbook", path: "/api/v1/orderbook" },
  { name: "get_trades", id: "getTrades", path: "/api/v1/trades" },
  {
    name: "get_price_limits",
    id: "getPriceLimit",
    path: "/api/v1/price-limits",
  },
  { name: "get_candles", id: "getCandles", path: "/api/v1/candles" },
] as const;
const examples = (path: string): Record<string, { value: any }> =>
  canonical.paths[path].get.responses["200"].content["application/json"]
    .examples;
const args = (name: string) =>
  name === "get_candles"
    ? { symbol: "005930", interval: "1d" }
    : { symbol: "005930" };
const allowed = (...ids: string[]) => new Set(["issueOAuth2Token", ...ids]);

/** production 서버를 SDK로 호출하고 받은 HTTP 요청의 경계를 함께 검사한다. */
async function withMarket(
  fake: FakeToss,
  work: (client: Client) => Promise<void>,
  timeoutMs?: number,
) {
  const server = createTossinvestServer({
    apiBase: fake.url,
    env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "7" },
    timeoutMs,
  });
  try {
    await withMcp(server, work);
  } finally {
    try {
      expect(fake.seen("DELETE")).toHaveLength(0);
      expect(
        fake.seen("POST").filter((request) => request.path !== "/oauth2/token"),
      ).toHaveLength(0);
      for (const request of fake.seen("GET")) {
        expect(request.body).toBe("");
        expect(request.headers.get("x-tossinvest-account")).toBeNull();
        expect(request.headers.get("authorization")).toBe(
          "Bearer fake-access-token-1",
        );
      }
    } finally {
      // 허용하지 않은 다른 GET도 수신 기록에 남긴 뒤 stop에서 거절한다.
      fake.stop();
    }
  }
}

for (const spec of tools) {
  for (const [label, example] of Object.entries(examples(spec.path))) {
    test(`${spec.name}: 공식 ${label} 예시를 SDK와 HTTP로 보존한다`, async () => {
      const fake = new FakeToss(allowed(spec.id));
      fake.on("GET", spec.path, example.value);
      await withMarket(fake, async (client) => {
        const symbol = label === "usStock" ? "AAPL" : "005930";
        const interval = label === "minuteCandles" ? "1m" : "1d";
        const input = { ...args(spec.name), symbol };
        if (spec.name === "get_candles") Object.assign(input, { interval });
        const { result, body } = await tool(client, spec.name, input);
        expect(result.isError).toBeUndefined();
        expect(result.structuredContent).toEqual(body);
        expect(body).toEqual({ result: example.value.result, metadata: {} });
        const request = fake.seen("GET")[0]!;
        expect(request.path).toBe(spec.path);
        expect(Object.fromEntries(request.query)).toEqual({
          symbol,
          ...(spec.name === "get_trades" ? { count: "50" } : {}),
          ...(spec.name === "get_candles"
            ? { interval, count: "100", adjusted: "true" }
            : {}),
        });
      });
      expect(fake.seen("GET")).toHaveLength(1);
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
    });
  }
}

test("production에 등록한 네 시세 도구가 실제 수신 operation 네 개와 인증에 도달한다", async () => {
  const fake = new FakeToss(allowed(...tools.map((spec) => spec.id)));
  for (const spec of tools)
    fake.on("GET", spec.path, Object.values(examples(spec.path))[0]!.value);
  await withMarket(fake, async (client) => {
    const listed = (await client.listTools()).tools;
    expect(
      new Set(
        listed
          .filter((item) => tools.some((spec) => spec.name === item.name))
          .map((item) => item.name),
      ),
    ).toEqual(new Set(tools.map((spec) => spec.name)));
    // 주문 실행 FINANCIAL 둘만 읽기 전용이 아니다.
    expect(
      listed
        .filter((item) => !item.annotations?.readOnlyHint)
        .map((item) => item.name)
        .sort(),
    ).toEqual(["cancel_order", "create_order"]);
    for (const spec of tools)
      expect(
        (await tool(client, spec.name, args(spec.name))).result.isError,
      ).toBeUndefined();
  });
  expect(fake.requests).toHaveLength(5);
  expect(
    new Set(
      fake.requests.map(
        (request) =>
          OPERATIONS.find((operation) =>
            matchesOperation(operation, request.method, request.path),
          )!.id,
      ),
    ),
  ).toEqual(allowed(...tools.map((spec) => spec.id)));
});

test("캔들 두 페이지를 수동으로 읽고 nextBefore와 + 오프셋을 그대로 전달한다", async () => {
  const fake = new FakeToss(allowed("getCandles"));
  const first = examples("/api/v1/candles").dailyCandles!.value;
  const last = examples("/api/v1/candles").lastPage!.value;
  fake.sequence("GET", "/api/v1/candles", [
    () => json(first),
    () => json(last),
  ]);
  await withMarket(fake, async (client) => {
    const page = await tool(client, "get_candles", {
      symbol: "0101N0",
      interval: "1d",
      count: 200,
      adjusted: false,
    });
    expect(fake.seen("GET")).toHaveLength(1);
    expect(page.body.result).toEqual(first.result);
    const before = (page.body.result as any).nextBefore;
    const next = await tool(client, "get_candles", {
      symbol: "0101N0",
      interval: "1d",
      count: 1,
      before,
      adjusted: false,
    });
    expect(next.body.result).toEqual(last.result);
    expect((next.body.result as any).nextBefore).toBeNull();
    expect(fake.seen("GET")).toHaveLength(2);
    const requests = fake.seen("GET");
    expect(requests[0]!.query.get("count")).toBe("200");
    expect(Object.fromEntries(requests[1]!.query)).toEqual({
      symbol: "0101N0",
      interval: "1d",
      count: "1",
      adjusted: "false",
      before,
    });
    expect(requests[1]!.query.toString()).toContain("%2B09%3A00");
  });
});

test("decimal의 끝자리와 null, 빈 배열, 미국 캔들 통화를 보존한다", async () => {
  const fake = new FakeToss(allowed(...tools.map((spec) => spec.id)));
  const decimal = "9007199254740993.9876543210987";
  const candle = structuredClone(examples("/api/v1/candles").lastPage!.value);
  Object.assign(candle.result.candles[0], {
    openPrice: decimal,
    highPrice: decimal,
    lowPrice: decimal,
    closePrice: decimal,
    volume: decimal,
    currency: "USD",
  });
  const orderbook = {
    result: {
      timestamp: null,
      currency: "USD",
      asks: [{ price: decimal, volume: decimal }],
      bids: [],
    },
  };
  fake.on("GET", "/api/v1/orderbook", orderbook);
  fake.on("GET", "/api/v1/trades", { result: [] });
  fake.on(
    "GET",
    "/api/v1/price-limits",
    examples("/api/v1/price-limits").usStock!.value,
  );
  fake.sequence("GET", "/api/v1/candles", [
    () => json(candle),
    () => json({ result: { candles: [], nextBefore: null } }),
  ]);
  await withMarket(fake, async (client) => {
    expect(
      (await tool(client, "get_orderbook", { symbol: "BRK.B" })).body.result,
    ).toEqual(orderbook.result);
    expect(
      (await tool(client, "get_trades", { symbol: "aapl", count: 1 })).body
        .result,
    ).toEqual([]);
    expect(fake.seen("GET", "/api/v1/trades")[0]!.query.get("count")).toBe("1");
    expect(
      (await tool(client, "get_price_limits", { symbol: "AAPL" })).body.result,
    ).toEqual(examples("/api/v1/price-limits").usStock!.value.result);
    expect(
      (await tool(client, "get_candles", { symbol: "AAPL", interval: "1m" }))
        .body.result,
    ).toEqual(candle.result);
    expect(
      (await tool(client, "get_candles", args("get_candles"))).body.result,
    ).toEqual({ candles: [], nextBefore: null });
  });
});

for (const spec of tools) {
  test(`${spec.name}: 잘못된 symbol은 인증을 포함해 HTTP 0회다`, async () => {
    const fake = new FakeToss(allowed(spec.id));
    await withMarket(fake, async (client) => {
      for (const symbol of [
        "",
        " ",
        "AAPL,MSFT",
        "../AAPL",
        "AAPL/NYSE",
        "AAPL?x=1",
        "한글",
        "AAPL\n",
      ])
        await expectFailure(client, "TOSSINVEST_INVALID_INPUT", spec.name, {
          ...args(spec.name),
          symbol,
        });
      expect(fake.requests).toHaveLength(0);
    });
  });
  test(`${spec.name}: 누락·타입·추가 인자도 HTTP 0회다`, async () => {
    const fake = new FakeToss(allowed(spec.id));
    await withMarket(fake, async (client) => {
      for (const input of [
        {},
        { ...args(spec.name), symbol: 12 },
        { ...args(spec.name), account: "7" },
        { ...args(spec.name), url: "https://example.com" },
      ]) {
        expect(
          (await client.callTool({ name: spec.name, arguments: input }))
            .isError,
        ).toBe(true);
      }
      expect(fake.requests).toHaveLength(0);
    });
  });
}

for (const [name, id, max] of [
  ["get_trades", "getTrades", 50],
  ["get_candles", "getCandles", 200],
] as const) {
  test(`${name}: count 범위와 정수 조건을 HTTP 전에 검사한다`, async () => {
    const fake = new FakeToss(allowed(id));
    await withMarket(fake, async (client) => {
      for (const count of [0, -1, 1.5, max + 1])
        await expectFailure(client, "TOSSINVEST_INVALID_INPUT", name, {
          ...args(name),
          count,
        });
      for (const count of ["1", null])
        expect(
          (await client.callTool({ name, arguments: { ...args(name), count } }))
            .isError,
        ).toBe(true);
      expect(fake.requests).toHaveLength(0);
    });
  });
}

test("캔들의 interval과 실제 날짜·오프셋, adjusted 타입을 HTTP 전에 검사한다", async () => {
  const fake = new FakeToss(allowed("getCandles"));
  await withMarket(fake, async (client) => {
    for (const interval of ["", "5m", "1h", "1D"])
      await expectFailure(client, "TOSSINVEST_INVALID_INPUT", "get_candles", {
        symbol: "AAPL",
        interval,
      });
    for (const before of [
      "",
      "2026-02-30T09:00:00+09:00",
      "2026-03-25",
      "2026-03-25T09:00:00",
      "2026-03-25T25:00:00Z",
      "2026-03-25T09:00:00+25:00",
    ])
      await expectFailure(client, "TOSSINVEST_INVALID_INPUT", "get_candles", {
        ...args("get_candles"),
        before,
      });
    for (const input of [
      { symbol: "AAPL" },
      { ...args("get_candles"), adjusted: "false" },
    ])
      expect(
        (await client.callTool({ name: "get_candles", arguments: input }))
          .isError,
      ).toBe(true);
    expect(fake.requests).toHaveLength(0);
  });
});

for (const spec of [tools[1], tools[2]]) {
  test(`${spec.name}: 서비스 실패를 성공이나 빈 결과로 바꾸지 않는다`, async () => {
    const fake = new FakeToss(allowed(spec.id));
    fake.fail("GET", spec.path, 500, "internal-error");
    await withMarket(fake, async (client) => {
      await expectFailure(
        client,
        "TOSSINVEST_UNAVAILABLE",
        spec.name,
        args(spec.name),
      );
    });
    expect(fake.seen("GET")).toHaveLength(1);
  });
}

// 각각 실제 MARKET_DATA와 MARKET_DATA_CHART 요청을 사용한다.
for (const spec of [tools[0], tools[3]]) {
  for (const [status, code, expected] of [
    [404, "stock-not-found", "TOSSINVEST_INVALID_INPUT"],
    [500, "internal-error", "TOSSINVEST_UNAVAILABLE"],
    [503, "internal-error", "TOSSINVEST_UNAVAILABLE"],
  ] as const) {
    test(`${spec.name}: HTTP ${status} 실패를 마스킹한다`, async () => {
      const fake = new FakeToss(allowed(spec.id));
      fake.fail("GET", spec.path, status, code);
      await withMarket(fake, async (client) => {
        await expectFailure(client, expected, spec.name, args(spec.name));
      });
      expect(fake.seen("GET")).toHaveLength(1);
    });
  }
  for (const [label, response] of [
    ["깨진 JSON", () => new Response("{broken", { status: 200 })],
    ["필수 필드 누락", () => json({ result: {} })],
    ["빈 본문", () => new Response(null, { status: 200 })],
    ["본문 상한 초과", () => new Response("x".repeat(RESPONSE_MAX_BYTES + 1))],
  ] as const) {
    test(`${spec.name}: ${label} 응답을 성공으로 취급하지 않는다`, async () => {
      const fake = new FakeToss(allowed(spec.id));
      fake.routes.set(`GET ${spec.path}`, response);
      await withMarket(fake, async (client) => {
        await expectFailure(
          client,
          "TOSSINVEST_UNAVAILABLE",
          spec.name,
          args(spec.name),
        );
      });
      expect(fake.seen("GET")).toHaveLength(1);
    });
  }
  test(`${spec.name}: HTTP timeout 후 재전송하지 않는다`, async () => {
    const fake = new FakeToss(allowed(spec.id));
    fake.routes.set(`GET ${spec.path}`, async () => {
      await Bun.sleep(150);
      return json(Object.values(examples(spec.path))[0]!.value);
    });
    await withMarket(
      fake,
      async (client) => {
        await expectFailure(
          client,
          "TOSSINVEST_NETWORK",
          spec.name,
          args(spec.name),
        );
      },
      60,
    );
    expect(fake.seen("GET")).toHaveLength(1);
  });
  test(`${spec.name}: 429는 한 번만 재시도하고 성공을 보존한다`, async () => {
    const fake = new FakeToss(allowed(spec.id));
    const example = Object.values(examples(spec.path))[0]!.value;
    fake.sequence("GET", spec.path, [
      apiError(429, "rate-limit-exceeded"),
      () => json(example),
    ]);
    await withMarket(
      fake,
      async (client) => {
        expect(
          (await tool(client, spec.name, args(spec.name))).body.result,
        ).toEqual(example.result);
      },
      1800,
    );
    expect(fake.seen("GET")).toHaveLength(2);
    expect(fake.seen("POST")).toHaveLength(1);
  });
  test(`${spec.name}: 반복 429는 두 전송 뒤 RATE_LIMITED다`, async () => {
    const fake = new FakeToss(allowed(spec.id));
    fake.fail("GET", spec.path, 429, "rate-limit-exceeded");
    await withMarket(
      fake,
      async (client) => {
        await expectFailure(
          client,
          "TOSSINVEST_RATE_LIMITED",
          spec.name,
          args(spec.name),
        );
      },
      1800,
    );
    expect(fake.seen("GET")).toHaveLength(2);
  });
}

for (const [blocked, independent] of [
  [tools[0], tools[3]],
  [tools[3], tools[0]],
] as const) {
  test(`${blocked.id}의 429 대기는 ${independent.id} 그룹을 막지 않는다`, async () => {
    const fake = new FakeToss(allowed(blocked.id, independent.id));
    fake.fail("GET", blocked.path, 429, "rate-limit-exceeded");
    const example = Object.values(examples(independent.path))[0]!.value;
    fake.on("GET", independent.path, example);
    await withMarket(
      fake,
      async (client) => {
        // 1초 cooldown이 250ms deadline보다 길어 추가 HTTP 없이 종료한다.
        await expectFailure(
          client,
          "TOSSINVEST_NETWORK",
          blocked.name,
          args(blocked.name),
        );
        expect(
          (await tool(client, independent.name, args(independent.name))).body
            .result,
        ).toEqual(example.result);
      },
      250,
    );
    expect(fake.seen("GET", blocked.path)).toHaveLength(1);
    expect(fake.seen("GET", independent.path)).toHaveLength(1);
    expect(fake.seen("POST")).toHaveLength(1);
  });
}
