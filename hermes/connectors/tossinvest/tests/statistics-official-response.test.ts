import { expect, test } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { SCHEMAS } from "../src/api-contract.ts";
import { Tossinvest } from "../src/client.ts";
import { officialFields } from "../src/official-response.ts";
import { createToolRegistration } from "../src/tool-registration.ts";
import canonical from "./fixtures/openapi-1.2.24.json";
import { FakeToss, credentials, tool, withMcp } from "./support.ts";

const statistics = [
  ["investor-trading", "getStockInvestorTrading"],
  ["program-trades", "getStockProgramTrades"],
  ["short-selling", "getStockShortSelling"],
  ["credit-trades", "getStockCreditTrades"],
  ["securities-lending", "getStockSecuritiesLending"],
] as const;
const marker = "additional-sensitive-statistics-extension";

/** 기대값과 별도로 모든 계층에 비공식 필드를 삽입한다. */
function withExtensions(value: any): any {
  if (Array.isArray(value)) return value.map(withExtensions);
  if (value === null || typeof value !== "object") return value;
  return {
    ...Object.fromEntries(
      Object.entries(value).map(([key, child]) => [key, withExtensions(child)]),
    ),
    token: marker,
    extension: { rows: [{ access_token: marker }] },
  };
}

function response(suffix: string): any {
  const paths: any = canonical.paths;
  return paths[`/api/v1/stocks/{symbol}/${suffix}`].get.responses["200"]
    .content["application/json"];
}

test.each(statistics)(
  "%s 공식 예시는 nullable·전체 필드·순서를 보존한다",
  (suffix) => {
    const contract = response(suffix);
    for (const example of Object.values(contract.examples) as any[]) {
      expect(
        officialFields(withExtensions(example.value), contract.schema),
      ).toEqual(example.value);
      expect(
        officialFields(withExtensions(example.value), {
          allOf: [...contract.schema.allOf].reverse(),
        }),
      ).toEqual(example.value);
    }
  },
);

test("설명 전용 result는 독립적으로 객체를 통과시키지 않는다", () => {
  expect(SCHEMAS.ApiResponse.properties.result).toEqual({});
  expect(
    canonical.components.schemas.ApiResponse.properties.result,
  ).not.toHaveProperty("type");
  expect(() =>
    officialFields({ result: { token: marker } }, SCHEMAS.ApiResponse),
  ).toThrow("TOSSINVEST_UNSUPPORTED_RESPONSE_SCHEMA");
  expect(() =>
    officialFields(
      { result: { token: marker } },
      canonical.components.schemas.ApiResponse,
    ),
  ).toThrow("TOSSINVEST_UNSUPPORTED_RESPONSE_SCHEMA");
});

test("nullable 참조 6개는 null·값·부재와 부호 있는 decimal을 보존한다", () => {
  const fields = [
    ["StockInvestorTradingRecord", "individual"],
    ["StockInvestorTradingRecord", "otherCorporation"],
    ["StockInvestorTradingRecord", "foreignerHolding"],
    ["StockInvestorTradingRecord", "cfd"],
    ["CreditTradeRecord", "marginLoan"],
    ["CreditTradeRecord", "stockLoan"],
  ];
  for (const [name, key] of fields) {
    const schema = (SCHEMAS as any)[name].properties[key!];
    expect(schema.oneOf).toHaveLength(2);
    const suffix =
      name === "CreditTradeRecord" ? "credit-trades" : "investor-trading";
    const rows = response(suffix).examples.daily.value.result.records;
    const known = rows.find((row: any) => row[key!] !== null)[key!];
    expect(officialFields(withExtensions(known), schema)).toEqual(known);
    expect(officialFields(null, schema)).toBeNull();
    const row = structuredClone(rows[0]);
    delete row[key!];
    expect(officialFields(withExtensions(row), (SCHEMAS as any)[name])).toEqual(
      row,
    );
  }
  const row = structuredClone(
    response("investor-trading").examples.daily.value.result.records[1],
  );
  row.foreigner.netBuyVolume = "-0.123456789012345678901234567";
  const upstream = withExtensions(row);
  upstream.foreigner.token = row.foreigner.netBuyVolume;
  expect(officialFields(upstream, SCHEMAS.StockInvestorTradingRecord)).toEqual(
    row,
  );
  for (const [path, operations] of Object.entries(canonical.paths)) {
    if (!path.includes("ranking")) continue;
    const contract = (operations as any).get.responses["200"].content[
      "application/json"
    ];
    for (const example of Object.values(contract.examples) as any[]) {
      expect(
        officialFields(withExtensions(example.value), contract.schema),
      ).toEqual(example.value);
    }
  }
});

test.each([
  {
    oneOf: [
      { $ref: "#/components/schemas/InvestorTradingVolume" },
      { type: "object" },
    ],
  },
  {
    oneOf: [
      { $ref: "#/components/schemas/InvestorTradingVolume" },
      { type: "null" },
      { type: "string" },
    ],
  },
  {
    anyOf: [
      { $ref: "#/components/schemas/InvestorTradingVolume" },
      { type: "null" },
    ],
  },
  { allOf: [{ properties: { result: { description: "placeholder" } } }] },
  { allOf: [{ type: "object", additionalProperties: { type: "string" } }] },
])("모호한 union과 map %j는 전체 오류로 차단한다", (schema) => {
  expect(() =>
    officialFields({ result: { token: marker }, token: marker }, schema),
  ).toThrow("TOSSINVEST_UNSUPPORTED_RESPONSE_SCHEMA");
});

// 통계 모듈 등록과 별도로 실제 client와 SDK에서 helper 경계를 검증한다.
test.each(statistics)(
  "%s SDK 전체 결과에는 추가 민감 표식이 없다",
  async (suffix, operation) => {
    const fake = new FakeToss(new Set(["issueOAuth2Token", operation]));
    const server = new McpServer({
      name: "official-statistics-boundary",
      version: "1.0.0",
    });
    const client = new Tossinvest({ apiBase: fake.url, env: credentials });
    const contract = response(suffix);
    createToolRegistration(server)(
      "official_statistics_boundary",
      {},
      { readOnlyHint: true },
      async () =>
        officialFields(
          await client.request(`/api/v1/stocks/005930/${suffix}`),
          contract.schema,
        ),
    );
    try {
      await withMcp(server, async (sdk) => {
        for (const [name, example] of Object.entries(
          contract.examples,
        ) as any[]) {
          fake.on(
            "GET",
            `/api/v1/stocks/005930/${suffix}`,
            withExtensions(example.value),
          );
          const { result, body } = await tool(
            sdk,
            "official_statistics_boundary",
          );
          expect(result.isError).not.toBe(true);
          expect(body).toEqual(example.value);
          expect(result.structuredContent).toEqual(example.value);
          expect(JSON.stringify(result)).not.toContain(marker);
          if (name === "daily") {
            const records = (body.result as any).records;
            const actual: Record<string, unknown> = {
              "investor-trading": records[1]?.foreigner?.netBuyVolume,
              "program-trades": records[0]?.arbitrage?.netBuyVolume,
              "short-selling": records[0]?.shortSellingAmount,
              "credit-trades": records[0]?.marginLoan?.balanceRate,
              "securities-lending": records[0]?.balanceAmount,
            };
            const known = {
              "investor-trading": "-319700",
              "program-trades": "-31100",
              "short-selling": "41250000000",
              "credit-trades": "0.0042",
              "securities-lending": "1218720000000",
            };
            expect(actual[suffix]).toBe(known[suffix]);
          }
        }
        const known = structuredClone(contract.examples.daily.value);
        delete known.result.nextUntil;
        if (suffix === "investor-trading")
          known.result.records[1].foreigner.netBuyVolume =
            "-0.123456789012345678901234567";
        fake.on(
          "GET",
          `/api/v1/stocks/005930/${suffix}`,
          withExtensions(known),
        );
        // 이 공식 decimal에는 대역 계좌번호와 같은 숫자가 있다. 값 denylist로 지우지 않는다.
        const result = await sdk.callTool({
          name: "official_statistics_boundary",
          arguments: {},
        });
        const body = JSON.parse(
          (result.content as Array<{ text: string }>)[0]!.text,
        );
        expect(result.isError).not.toBe(true);
        expect(body).toEqual(known);
        expect(result.structuredContent).toEqual(known);
        expect(JSON.stringify(result)).not.toContain(marker);
      });
    } finally {
      fake.stop();
    }
  },
);
