import { expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { CONTRACT_SHA256, OPERATIONS, SCHEMAS } from "../src/api-contract.ts";
import { Tossinvest } from "../src/client.ts";
import { RESPONSE_MAX_BYTES } from "../src/constants.ts";
import { GROUP_LIMITS } from "../src/rate-limit.ts";
import { registerStockStatisticsTools } from "../src/stock-statistics-tools.ts";
import { createToolRegistration } from "../src/tool-registration.ts";
import {
  FakeToss,
  apiError,
  credentials,
  expectFailure,
  json,
  tokenPrefix,
  tool,
  withMcp,
} from "./support.ts";

const cases = [
  ["getStockInvestorTrading", "get_stock_investor_trading", "investor-trading"],
  ["getStockProgramTrades", "get_stock_program_trades", "program-trades"],
  ["getStockShortSelling", "get_stock_short_selling", "short-selling"],
  ["getStockCreditTrades", "get_stock_credit_trades", "credit-trades"],
  [
    "getStockSecuritiesLending",
    "get_stock_securities_lending",
    "securities-lending",
  ],
] as const;
const allowed = new Set<string>([
  "issueOAuth2Token",
  ...cases.map(([id]) => id),
]);
const fixture = readFileSync(`${import.meta.dir}/fixtures/openapi-1.2.24.json`);
const official = JSON.parse(fixture.toString());
const pathFor = (segment: string, symbol = "005930") =>
  `/api/v1/stocks/${symbol}/${segment}`;
const operation = (segment: string) =>
  official.paths[pathFor(segment, "{symbol}")].get;
const example = (segment: string, key = "daily") =>
  structuredClone(
    operation(segment).responses["200"].content["application/json"].examples[
      key
    ].value,
  );

function server(fake: FakeToss, timeoutMs?: number) {
  const mcp = new McpServer({
    name: "stock-statistics-test",
    version: "1.0.0",
  });
  registerStockStatisticsTools(
    createToolRegistration(mcp),
    new Tossinvest({
      apiBase: fake.url,
      env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "7" },
      timeoutMs,
    }),
  );
  return mcp;
}

/** 실제 수신한 HTTP의 인증과 읽기 전용 경계를 확인한다. */
function expectReadOnlyTraffic(fake: FakeToss) {
  expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
  const auth = fake.seen("POST", "/oauth2/token")[0]!;
  expect(auth.headers.get("authorization")).toBeNull();
  expect(auth.headers.get("x-tossinvest-account")).toBeNull();
  expect(auth.headers.get("content-type")).toBe(
    "application/x-www-form-urlencoded",
  );
  expect(Object.fromEntries(new URLSearchParams(auth.body))).toEqual({
    grant_type: "client_credentials",
    client_id: credentials.TOSSINVEST_CLIENT_ID,
    client_secret: credentials.TOSSINVEST_CLIENT_SECRET,
  });
  expect(
    fake.requests.filter(
      (request) => request.method !== "GET" && request.path !== "/oauth2/token",
    ),
  ).toHaveLength(0);
  expect(fake.seen("DELETE")).toHaveLength(0);
  for (const request of fake.seen("GET")) {
    expect(request.body).toBe("");
    expect(request.headers.get("x-tossinvest-account")).toBeNull();
    expect(request.headers.get("authorization")).toBe(
      `${"Bearer "}${tokenPrefix}1`,
    );
    expect(request.headers.get("accept")).toBe("application/json");
  }
}

test("공식 1.2.24의 SHA와 5개 method, parameter, response, 공유 한도는 fixture와 일치한다", () => {
  expect(official.info.version).toBe("1.2.24");
  expect(createHash("sha256").update(fixture).digest("hex")).toBe(
    CONTRACT_SHA256,
  );
  expect(CONTRACT_SHA256).toBe(
    "7e753c21dc4b938d8f9610ce3efd6542783018977cb030e39db5a006f5b59d40",
  );
  expect(GROUP_LIMITS.STOCK_TRADING_TREND).toBe(10);
  expect(GROUP_LIMITS.AUTH).toBe(5);
  for (const [id, , segment] of cases) {
    const contract = OPERATIONS.find((entry) => entry.id === id)!;
    const source = operation(segment);
    expect(contract.method).toBe("GET");
    expect(contract.path).toBe(pathFor(segment, "{symbol}"));
    expect(contract.group).toBe("STOCK_TRADING_TREND");
    expect(contract.input.body).toEqual({});
    expect(contract.input.parameters).toEqual(
      source.parameters.map((parameter: any) => {
        const resolved = parameter.$ref
          ? official.components.parameters[parameter.$ref.split("/").at(-1)]
          : parameter;
        return {
          name: resolved.name,
          in: resolved.in,
          required: resolved.required ?? false,
          schema: resolved.schema,
        };
      }),
    );
    expect(contract.responses["200"]).toEqual(
      source.responses["200"].content["application/json"].schema,
    );
    // 응답에서 실제 참조하는 하위 schema까지 원문과 대조한다.
    const visit = (schema: any, visited = new Set<string>()) => {
      if (!schema || typeof schema !== "object") return;
      if (schema.$ref) {
        const name = schema.$ref.split("/").at(-1);
        if (visited.has(name)) return;
        visited.add(name);
        const raw = official.components.schemas[name];
        const normalize = (value: any): any => {
          if (Array.isArray(value)) return value.map(normalize);
          if (!value || typeof value !== "object") return value;
          return Object.fromEntries(
            Object.entries(value)
              .filter(
                ([key]) =>
                  ![
                    "description",
                    "example",
                    "examples",
                    "title",
                    "deprecated",
                  ].includes(key),
              )
              .map(([key, child]) => [key, normalize(child)]),
          );
        };
        expect(SCHEMAS[name]).toEqual(normalize(raw));
        visit(raw, visited);
      }
      for (const child of Object.values(schema)) visit(child, visited);
    };
    visit(contract.responses["200"]);
  }
});

for (const [, name, segment] of cases) {
  test(`${name}: SDK의 공식 예시 전체, 기본 10, 실제 수신 HTTP와 readOnlyHint`, async () => {
    const fake = new FakeToss(allowed);
    try {
      const wire = example(segment);
      fake.on("GET", pathFor(segment), wire);
      await withMcp(server(fake), async (client) => {
        const listed = await client.listTools();
        expect(listed.tools.map((entry) => entry.name).sort()).toEqual(
          cases.map(([, entry]) => entry).sort(),
        );
        for (const entry of listed.tools)
          expect(entry.annotations?.readOnlyHint).toBe(true);
        const input = listed.tools.find((entry) => entry.name === name)!
          .inputSchema as any;
        expect(input.additionalProperties).toBe(false);
        expect(input.required).toContain("symbol");
        expect(input.properties.count).toMatchObject({
          type: "integer",
          minimum: 1,
          maximum: 100,
          default: 10,
        });
        expect(input.properties.until.format).toBe("date");
        const { result, body } = await tool(client, name, { symbol: "005930" });
        expect(result.isError).not.toBe(true);
        expect(body).toEqual({ result: wire.result, metadata: {} });
        expect(result.structuredContent).toEqual(body);
      });
      expect(fake.seen("GET")).toHaveLength(1);
      expect(Object.fromEntries(fake.seen("GET")[0]!.query)).toEqual({
        count: "10",
      });
      expectReadOnlyTraffic(fake);
    } finally {
      fake.stop();
    }
  });

  test(`${name}: count 양 끝값, 실제 달력, 영문과 점, nextUntil의 두 페이지와 빈 결과`, async () => {
    const fake = new FakeToss(allowed);
    try {
      const first = example(segment);
      fake.sequence("GET", pathFor(segment, "0101N0.A-B"), [
        () => json(first),
        () => json(example(segment, "noData")),
      ]);
      await withMcp(server(fake), async (client) => {
        const initial = await tool(client, name, {
          symbol: "0101N0.A-B",
          count: 1,
          until: "2024-02-29",
        });
        expect(initial.body.result).toEqual(first.result);
        const last = await tool(client, name, {
          symbol: "0101N0.A-B",
          count: 100,
          until: (initial.body.result as any).nextUntil,
        });
        expect(last.body).toEqual({
          result: { records: [], nextUntil: null },
          metadata: {},
        });
      });
      const reads = fake.seen("GET");
      expect(reads).toHaveLength(2);
      expect(reads[0]!.path).toBe(pathFor(segment, "0101N0.A-B"));
      expect(Object.fromEntries(reads[0]!.query)).toEqual({
        count: "1",
        until: "2024-02-29",
      });
      expect(Object.fromEntries(reads[1]!.query)).toEqual({
        count: "100",
        until: first.result.nextUntil,
      });
      expectReadOnlyTraffic(fake);
    } finally {
      fake.stop();
    }
  });

  test(`${name}: caller URL, account, token, extra와 잘못된 날짜, 점 segment는 인증을 포함해 HTTP 0회`, async () => {
    const fake = new FakeToss(allowed);
    try {
      await withMcp(server(fake), async (client) => {
        const invalid: Record<string, unknown>[] = [
          {},
          ...[
            "",
            ".",
            "..",
            "005930/other",
            "../005930",
            "%2e%2e",
            "A?count=1",
            "A#fragment",
            "A\\B",
            " A",
            "A\n",
            "https://example.invalid",
            "A,B",
          ].map((symbol) => ({ symbol })),
          ...[0, 101, -1, 1.5, "10", null].map((count) => ({
            symbol: "005930",
            count,
          })),
          ...[
            "2023-02-29",
            "1900-02-29",
            "2024-02-30",
            "2026-04-31",
            "2026-00-01",
            "2026-13-01",
            "2026-01-00",
            "2026-1-01",
            "2026-01-01T00:00:00Z",
            "2026-01-01\n",
            "",
            null,
          ].map((until) => ({ symbol: "005930", until })),
          ...[
            "account",
            "account_seq",
            "accountSeq",
            "token",
            "access_token",
            "url",
            "apiBase",
            "extra",
            "market",
          ].map((key) => ({ symbol: "005930", [key]: "caller-input" })),
        ];
        for (const args of invalid) {
          const response = await client.callTool({ name, arguments: args });
          expect(response.isError).toBe(true);
        }
      });
      expect(fake.requests).toHaveLength(0);
    } finally {
      fake.stop();
    }
  });

  test(`${name}: unsupported-market, 모르는 error, 403과 500은 성공으로 바꾸지 않는다`, async () => {
    const fake = new FakeToss(allowed);
    try {
      fake.sequence("GET", pathFor(segment, "AAPL"), [
        apiError(400, "unsupported-market"),
        apiError(400, "future-error"),
        apiError(403, "forbidden"),
        apiError(500, "internal-server-error"),
      ]);
      await withMcp(server(fake), async (client) => {
        for (const code of [
          "TOSSINVEST_INVALID_INPUT",
          "TOSSINVEST_INVALID_INPUT",
          "TOSSINVEST_FORBIDDEN",
          "TOSSINVEST_UNAVAILABLE",
        ])
          await expectFailure(client, code, name, { symbol: "AAPL" });
      });
      expect(fake.seen("GET")).toHaveLength(4);
      expectReadOnlyTraffic(fake);
    } finally {
      fake.stop();
    }
  });

  test(`${name}: 429는 한 번만 재송신하고 연속 429는 명확한 오류다`, async () => {
    const fake = new FakeToss(allowed);
    try {
      const limited = () =>
        json(
          {
            error: {
              requestId: "synthetic",
              code: "rate-limit-exceeded",
              message: "synthetic",
            },
          },
          429,
          { "retry-after": "0" },
        );
      fake.sequence("GET", pathFor(segment), [
        limited,
        () => json(example(segment)),
        limited,
        limited,
      ]);
      await withMcp(server(fake), async (client) => {
        expect(
          (await tool(client, name, { symbol: "005930" })).result.isError,
        ).not.toBe(true);
        await expectFailure(client, "TOSSINVEST_RATE_LIMITED", name, {
          symbol: "005930",
        });
      });
      expect(fake.seen("GET")).toHaveLength(4);
      expectReadOnlyTraffic(fake);
    } finally {
      fake.stop();
    }
  });

  test(`${name}: 잘못된 행을 삭제하지 않고 응답 전체를 실패로 처리한다`, async () => {
    const fake = new FakeToss(allowed);
    try {
      const bad = example(segment);
      delete bad.result.records[1].date;
      const required: Record<string, string[]> = {
        "investor-trading": ["foreigner", "buyVolume"],
        "program-trades": ["arbitrage", "netBuyVolume"],
        "short-selling": ["shortSellingAmount"],
        "credit-trades": ["marginLoan", "balanceRate"],
        "securities-lending": ["balanceAmount"],
      };
      const numeric = example(segment);
      const keys = required[segment]!;
      let holder = numeric.result.records[0];
      for (const key of keys.slice(0, -1)) holder = holder[key];
      holder[keys.at(-1)!] = 123;
      const tooLong = example(segment);
      let longHolder = tooLong.result.records[0];
      for (const key of keys.slice(0, -1)) longHolder = longHolder[key];
      longHolder[keys.at(-1)!] = "9".repeat(31);
      fake.sequence("GET", pathFor(segment), [
        () => json(bad),
        () => json(numeric),
        () => json(tooLong),
        () => new Response("{bad"),
        () => json({ result: { records: null } }),
      ]);
      await withMcp(server(fake), async (client) => {
        for (let i = 0; i < 5; i++)
          await expectFailure(client, "TOSSINVEST_UNAVAILABLE", name, {
            symbol: "005930",
          });
      });
      expect(fake.seen("GET")).toHaveLength(5);
    } finally {
      fake.stop();
    }
  });

  test(`${name}: 1 MiB는 보존하고 초과는 실패한다`, async () => {
    const fake = new FakeToss(allowed);
    try {
      const raw = JSON.stringify(example(segment, "noData"));
      const bounded =
        raw +
        " ".repeat(RESPONSE_MAX_BYTES - new TextEncoder().encode(raw).length);
      fake.sequence("GET", pathFor(segment), [
        () => new Response(bounded),
        () => new Response(bounded + " "),
      ]);
      await withMcp(server(fake), async (client) => {
        expect(
          (await tool(client, name, { symbol: "005930" })).body.result,
        ).toEqual({ records: [], nextUntil: null });
        await expectFailure(client, "TOSSINVEST_UNAVAILABLE", name, {
          symbol: "005930",
        });
      });
      expect(fake.seen("GET")).toHaveLength(2);
      expectReadOnlyTraffic(fake);
    } finally {
      fake.stop();
    }
  });

  test(`${name}: 수신한 GET이 HTTP deadline을 넘으면 실패한다`, async () => {
    const fake = new FakeToss(allowed);
    try {
      fake.routes.set(`GET ${pathFor(segment)}`, async () => {
        await Bun.sleep(100);
        return json(example(segment));
      });
      await withMcp(server(fake, 30), async (client) => {
        await expectFailure(client, "TOSSINVEST_NETWORK", name, {
          symbol: "005930",
        });
      });
      expect(fake.seen("GET")).toHaveLength(1);
      expectReadOnlyTraffic(fake);
    } finally {
      fake.stop();
    }
  });

  test(`${name}: 공식 100개 기록을 보존하고 중첩 객체와 배열의 민감 확장키를 제거한다`, async () => {
    const fake = new FakeToss(allowed);
    try {
      const wire = example(segment);
      wire.result.records = Array.from({ length: 100 }, () =>
        structuredClone(wire.result.records.at(-1)),
      );
      const expected = structuredClone(wire.result);
      // 공식 필드 사이의 비공식 키와 그 안의 객체·배열에 합성 민감값을 끼운다.
      const inject = (value: any) => {
        if (Array.isArray(value)) {
          value.forEach(inject);
        } else if (value && typeof value === "object") {
          Object.values(value).forEach(inject);
          value.access_token = "synthetic-sensitive-token";
          value.privateExtension = [
            {
              accountNo: "synthetic-sensitive-account",
              nested: { secret: "synthetic-sensitive-secret" },
            },
          ];
        }
      };
      inject(wire);
      fake.on("GET", pathFor(segment), wire);
      await withMcp(server(fake), async (client) => {
        const { result, body } = await tool(client, name, {
          symbol: "005930",
          count: 100,
        });
        expect(body).toEqual({ result: expected, metadata: {} });
        expect((body.result as any).records).toHaveLength(100);
        expect(result.structuredContent).toEqual(body);
        for (const secret of [
          "synthetic-sensitive-token",
          "synthetic-sensitive-account",
          "synthetic-sensitive-secret",
        ])
          expect(JSON.stringify(result)).not.toContain(secret);
      });
      expect(fake.seen("GET")).toHaveLength(1);
      expectReadOnlyTraffic(fake);
    } finally {
      fake.stop();
    }
  });
}

test("독립적인 수치 기대값으로 등록외국인, 기관 7개 분류, CFD, KRW와 비율을 확인한다", async () => {
  const fake = new FakeToss(allowed);
  try {
    for (const [, , segment] of cases)
      fake.on("GET", pathFor(segment), example(segment));
    await withMcp(server(fake), async (client) => {
      const records = async (name: string) =>
        ((await tool(client, name, { symbol: "005930" })).body.result as any)
          .records;
      const investor = await records("get_stock_investor_trading");
      expect(investor[0].individual).toBeNull();
      expect(investor[0].institution.breakdown).toBeNull();
      expect(investor[0].institution.netBuyVolume).toBe("-113200");
      expect(Object.keys(investor[1].institution.breakdown)).toEqual([
        "financialInvestment",
        "insurance",
        "trust",
        "privateEquityFund",
        "bank",
        "otherFinancialInstitution",
        "pensionFund",
      ]);
      expect(investor[1].foreigner.netBuyVolume).toBe("-319700");
      expect(investor[1].foreignerHolding.holdingRate).toBe("0.5089");
      expect(investor[1].cfd.buyBalanceRate).toBe("0.0002");
      expect(investor[1].updatedAt).toBe("2026-07-17T09:12:43+09:00");
      const program = await records("get_stock_program_trades");
      expect(program[0].arbitrage.netBuyVolume).toBe("-31100");
      expect(program[0].nonArbitrage.netBuyVolume).toBe("105300");
      expect(program[0]).not.toHaveProperty("updatedAt");
      const short = await records("get_stock_short_selling");
      expect(short[0].shortSellingAmount).toBe("41250000000");
      expect(short[0].shortSellingVolumeRate).toBe("0.03215");
      expect(short[0].shortSellingAmountRate).toBe("0.0318");
      const credit = await records("get_stock_credit_trades");
      expect(credit[0].marginLoan.balanceQuantity).toBe("2513400");
      expect(credit[0].marginLoan.balanceRate).toBe("0.0042");
      expect(credit[0].marginLoan.tradingRate).toBe("0.09");
      expect(credit[0].stockLoan.balanceQuantity).toBe("45200");
      expect(credit[0].updatedAt).toBe("2026-07-17T02:35:00+09:00");
      const lending = await records("get_stock_securities_lending");
      expect(lending[0].executionQuantity).toBe("210500");
      expect(lending[0].repaymentQuantity).toBe("185300");
      expect(lending[0].balanceAmount).toBe("1218720000000");
      expect(lending[0].updatedAt).toBe("2026-07-17T19:03:21+09:00");
    });
    expect(fake.seen("GET")).toHaveLength(5);
    expectReadOnlyTraffic(fake);
  } finally {
    fake.stop();
  }
});

test("nullable, optional 부재와 긴 decimal 문자열을 변환하지 않는다", async () => {
  const fake = new FakeToss(allowed);
  try {
    const investor = example("investor-trading");
    const minimal = {
      date: "2026-07-17",
      updatedAt: "2026-07-17T14:35:08+09:00",
      foreigner: {
        buyVolume: "999999999999999999999999999999",
        sellVolume: "0",
        netBuyVolume: "999999999999999999999999999999",
      },
      institution: { buyVolume: "0", sellVolume: "1", netBuyVolume: "-1" },
    };
    investor.result.records = [minimal];
    delete investor.result.nextUntil;
    fake.on("GET", pathFor("investor-trading"), investor);
    const short = example("short-selling", "rateUnavailable");
    short.result.records.push({
      ...short.result.records[0],
      shortSellingVolumeRate: "0",
      shortSellingAmountRate: "0",
    });
    fake.on("GET", pathFor("short-selling"), short);
    const credit = example("credit-trades");
    credit.result.records[0].stockLoan = null;
    credit.result.records[1].marginLoan = null;
    credit.result.records[0].marginLoan.balanceRate =
      "0.9876543210987654321098765432";
    credit.result.records.push({
      date: "2026-07-14",
      updatedAt: "2026-07-15T02:00:00+09:00",
    });
    fake.on("GET", pathFor("credit-trades"), credit);
    await withMcp(server(fake), async (client) => {
      const a = await tool(client, "get_stock_investor_trading", {
        symbol: "005930",
        until: "2000-02-29",
      });
      expect(a.body.result).toEqual({ records: [minimal] });
      expect((a.body.result as any).records[0].foreigner.buyVolume).toBe(
        "999999999999999999999999999999",
      );
      const b = await tool(client, "get_stock_short_selling", {
        symbol: "005930",
      });
      expect(b.body.result).toEqual(short.result);
      expect(
        (b.body.result as any).records[0].shortSellingVolumeRate,
      ).toBeNull();
      expect((b.body.result as any).records[1].shortSellingVolumeRate).toBe(
        "0",
      );
      const c = await tool(client, "get_stock_credit_trades", {
        symbol: "005930",
      });
      expect(c.body.result).toEqual(credit.result);
      expect((c.body.result as any).records[0].marginLoan.balanceRate).toBe(
        "0.9876543210987654321098765432",
      );
      expect(c.result.structuredContent).toEqual(c.body);
    });
  } finally {
    fake.stop();
  }
});

test("429 대기도 같은 deadline에 포함하며 재송신 전에 실패한다", async () => {
  const fake = new FakeToss(allowed);
  try {
    fake.routes.set(`GET ${pathFor("investor-trading")}`, () =>
      json(
        {
          error: {
            requestId: "synthetic",
            code: "rate-limit-exceeded",
            message: "synthetic",
          },
        },
        429,
        { "retry-after": "1" },
      ),
    );
    await withMcp(server(fake, 80), async (client) => {
      await expectFailure(
        client,
        "TOSSINVEST_NETWORK",
        "get_stock_investor_trading",
        { symbol: "005930" },
      );
    });
    expect(fake.seen("GET")).toHaveLength(1);
    expectReadOnlyTraffic(fake);
  } finally {
    fake.stop();
  }
});

test("서로 다른 client의 5개 통계 operation도 같은 초당 10회 그룹을 공유한다", async () => {
  const fake = new FakeToss(allowed);
  const arrivals: number[] = [];
  try {
    for (const [, , segment] of cases)
      fake.routes.set(`GET ${pathFor(segment)}`, () => {
        arrivals.push(performance.now());
        return json(example(segment, "noData"));
      });
    await withMcp(server(fake), (first) =>
      withMcp(server(fake), async (second) => {
        const results = await Promise.all(
          cases.map(([, name], index) =>
            tool(index % 2 ? second : first, name, { symbol: "005930" }),
          ),
        );
        for (const { result } of results) expect(result.isError).not.toBe(true);
      }),
    );
    expect(arrivals).toHaveLength(5);
    for (let i = 1; i < arrivals.length; i++)
      expect(arrivals[i]! - arrivals[i - 1]!).toBeGreaterThanOrEqual(100);
    expect(new Set(fake.seen("GET").map((request) => request.path))).toEqual(
      new Set(cases.map(([, , segment]) => pathFor(segment))),
    );
    expectReadOnlyTraffic(fake);
  } finally {
    fake.stop();
  }
});
