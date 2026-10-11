import { describe, expect, test } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { Tossinvest } from "../src/client.ts";
import { operationFor } from "../src/api-contract.ts";
import { registerSectorTools } from "../src/sector-tools.ts";
import { createToolRegistration } from "../src/tool-registration.ts";
import {
  FakeToss,
  apiError,
  credentials,
  expectFailure,
  json,
  tool,
  withMcp,
} from "./support.ts";
import fixture from "./fixtures/openapi-1.2.24.json";

const paths = fixture.paths as Record<string, any>;
const examples = (path: string) =>
  paths[path].get.responses["200"].content["application/json"].examples;
const example = (path: string, name: string) =>
  structuredClone(examples(path)[name].value);

const operations = new Set([
  "issueOAuth2Token",
  "listSectors",
  "getSectorRankings",
  "getSector",
  "getSectorStocks",
  "getSectorEtfs",
]);
const cases = [
  {
    name: "list_sectors",
    path: "/api/v1/sectors",
    template: "/api/v1/sectors",
    args: {},
    sample: "sectors",
    id: "listSectors",
  },
  {
    name: "get_sector_rankings",
    path: "/api/v1/sectors/rankings",
    template: "/api/v1/sectors/rankings",
    args: { type: "TOP_GAINERS", marketCountry: "KR", duration: "1d" },
    sample: "topGainers",
    id: "getSectorRankings",
  },
  {
    name: "get_sector",
    path: "/api/v1/sectors/100",
    template: "/api/v1/sectors/{sectorId}",
    args: { sectorId: "100", marketCountry: "KR" },
    sample: "sector",
    id: "getSector",
  },
  {
    name: "get_sector_stocks",
    path: "/api/v1/sectors/100/stocks",
    template: "/api/v1/sectors/{sectorId}/stocks",
    args: { sectorId: "100", marketCountry: "KR" },
    sample: "krStocks",
    id: "getSectorStocks",
  },
  {
    name: "get_sector_etfs",
    path: "/api/v1/sectors/100/etfs",
    template: "/api/v1/sectors/{sectorId}/etfs",
    args: { sectorId: "100", marketCountry: "KR" },
    sample: "krEtfs",
    id: "getSectorEtfs",
  },
] as const;

async function session(
  work: (
    fake: FakeToss,
    client: Parameters<Parameters<typeof withMcp>[1]>[0],
  ) => Promise<void>,
  timeoutMs = 4000,
) {
  const fake = new FakeToss(operations);
  try {
    const server = new McpServer({
      name: "sector-module-test",
      version: "1.0.0",
    });
    registerSectorTools(
      createToolRegistration(server),
      new Tossinvest({ apiBase: fake.url, env: credentials, timeoutMs }),
    );
    await withMcp(server, (client) => work(fake, client));
  } finally {
    fake.stop();
  }
}

describe("섹터 조회 모듈의 SDK와 HTTP", () => {
  test("공식 5개 operation에 도달하고 전체 결과와 읽기 권한을 보존한다", async () => {
    await session(async (fake, client) => {
      const listed = await client.listTools();
      expect(listed.tools.map((item) => item.name).sort()).toEqual(
        cases.map((item) => item.name).sort(),
      );
      for (const item of listed.tools) {
        expect(item.annotations?.readOnlyHint).toBe(true);
        expect(item.inputSchema.additionalProperties).toBe(false);
      }
      for (const item of cases) {
        const body = example(item.template, item.sample);
        fake.on("GET", item.path, body);
        const reply = await tool(client, item.name, item.args);
        expect(reply.result.isError).not.toBe(true);
        expect(reply.body).toEqual({ result: body.result, metadata: {} });
        expect(reply.result.structuredContent).toEqual(reply.body);
        const received = fake.seen("GET", item.path);
        expect(received).toHaveLength(1);
        const request = received[0]!;
        const { sectorId: _sectorId, ...query } = item.args as Record<
          string,
          string
        >;
        expect(Object.fromEntries(request.query)).toEqual(query);
        expect(request.body).toBe("");
        expect(request.headers.get("authorization")).toMatch(
          /^Bearer fake-access-token-/,
        );
        expect(request.headers.has("x-tossinvest-account")).toBe(false);
        expect(operationFor(request.method, request.path).id).toBe(item.id);
        expect(paths[item.template].get.operationId).toBe(item.id);
      }
      expect(
        new Set(
          fake
            .seen("GET")
            .map((request) => operationFor(request.method, request.path).id),
        ),
      ).toEqual(new Set(cases.map((item) => item.id)));
      expect(fake.seen("GET")).toHaveLength(5);
      expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
      expect(
        fake.requests.filter(
          (request) =>
            ["POST", "DELETE"].includes(request.method) &&
            request.path !== "/oauth2/token",
        ),
      ).toHaveLength(0);
    });
  });

  test("두 시장·두 랭킹 기준·6개 기간의 모든 조합을 전달한다", async () => {
    await session(async (fake, client) => {
      const path = "/api/v1/sectors/rankings";
      fake.on("GET", path, example(path, "emptyRankings"));
      for (const marketCountry of ["KR", "US"])
        for (const type of ["TOP_GAINERS", "MARKET_TRADING_AMOUNT"])
          for (const duration of ["1d", "1w", "1mo", "3mo", "6mo", "1y"]) {
            expect(
              (
                await tool(client, "get_sector_rankings", {
                  type,
                  marketCountry,
                  duration,
                })
              ).body,
            ).toEqual({ result: { rankings: [] }, metadata: {} });
            expect(Object.fromEntries(fake.seen("GET").at(-1)!.query)).toEqual({
              type,
              marketCountry,
              duration,
            });
          }
      expect(fake.seen("GET")).toHaveLength(24);
    });
  }, 10000);

  test("공식 예시 전부의 계층·null·decimal·ETF와 빈 배열을 보존한다", async () => {
    await session(async (fake, client) => {
      for (const item of cases) {
        for (const [name, value] of Object.entries(
          examples(item.template),
        ) as Array<[string, any]>) {
          const body = structuredClone(value.value);
          fake.on("GET", item.path, body);
          const args = {
            ...item.args,
            ...(item.name === "list_sectors"
              ? {}
              : {
                  marketCountry:
                    name.startsWith("us") || name === "marketTradingAmount"
                      ? "US"
                      : "KR",
                }),
          };
          const reply = await tool(client, item.name, args);
          expect(reply.body).toEqual({ result: body.result, metadata: {} });
        }
      }
      const detail = example("/api/v1/sectors/{sectorId}", "sector");
      detail.result.summary = null;
      detail.result.depth = 0;
      detail.result.parentSectorId = null;
      fake.on("GET", "/api/v1/sectors/100", detail);
      expect(
        (
          await tool(client, "get_sector", {
            sectorId: "100",
            marketCountry: "US",
          })
        ).body.result,
      ).toEqual(detail.result);
      const stocks = example("/api/v1/sectors/{sectorId}/stocks", "krStocks");
      stocks.result.stocks[0].volume = "0.9876543210987654321098765432";
      stocks.result.stocks[0].marketCapitalization = null;
      fake.on("GET", "/api/v1/sectors/100/stocks", stocks);
      fake.on("GET", "/api/v1/sectors/42/stocks", stocks);
      for (const sectorId of ["100", "42"])
        expect(
          (
            await tool(client, "get_sector_stocks", {
              sectorId,
              marketCountry: "KR",
            })
          ).body.result,
        ).toEqual(stocks.result);
      const rankings = example("/api/v1/sectors/rankings", "topGainers");
      // 설명에는 항상 값이 있다고 쓰였지만 공식 schema는 null을 허용한다.
      rankings.result.rankings[0].changeRate = null;
      fake.on("GET", "/api/v1/sectors/rankings", rankings);
      expect(
        (await tool(client, "get_sector_rankings", cases[1].args)).body.result,
      ).toEqual(rankings.result);
    });
  }, 10000);

  test("40자 식별자를 한 path segment로 보낸다", async () => {
    await session(async (fake, client) => {
      const sectorId = "Ab_9-".repeat(8);
      for (const item of cases.slice(2)) {
        const path = item.path.replace("100", sectorId);
        fake.on("GET", path, example(item.template, item.sample));
        expect(
          (await tool(client, item.name, { sectorId, marketCountry: "US" }))
            .result.isError,
        ).not.toBe(true);
        expect(fake.seen("GET", path)).toHaveLength(1);
        expect(Object.fromEntries(fake.seen("GET", path)[0]!.query)).toEqual({
          marketCountry: "US",
        });
      }
    });
  });

  test("잘못된 입력은 인증과 HTTP 전송 전에 거절한다", async () => {
    await session(async (fake, client) => {
      for (const item of cases.slice(2)) {
        for (const sectorId of [
          "",
          "x".repeat(41),
          "../100",
          "100/stocks",
          "%2F",
          "a?x=1",
          "a#b",
          "가",
          " x",
        ])
          expect(
            (
              await client.callTool({
                name: item.name,
                arguments: { sectorId, marketCountry: "KR" },
              })
            ).isError,
          ).toBe(true);
        for (const marketCountry of ["JP", "kr", "", null])
          expect(
            (
              await client.callTool({
                name: item.name,
                arguments: { sectorId: "100", marketCountry },
              })
            ).isError,
          ).toBe(true);
        expect(
          (
            await client.callTool({
              name: item.name,
              arguments: { sectorId: "100" },
            })
          ).isError,
        ).toBe(true);
      }
      for (const args of [
        { ...cases[1].args, type: "OTHER" },
        { ...cases[1].args, duration: "1m" },
        { ...cases[1].args, marketCountry: "JP" },
        { type: "TOP_GAINERS", marketCountry: "KR" },
      ])
        expect(
          (
            await client.callTool({
              name: "get_sector_rankings",
              arguments: args,
            })
          ).isError,
        ).toBe(true);
      for (const item of cases)
        expect(
          (
            await client.callTool({
              name: item.name,
              arguments: { ...item.args, page: 1 },
            })
          ).isError,
        ).toBe(true);
      expect(fake.requests).toHaveLength(0);
      expect(fake.issued).toBe(0);
    });
  });

  test("404와 5xx는 안전한 오류로 알리고 재송신하지 않는다", async () => {
    for (const [status, code, expected] of [
      [404, "sector-not-found", "TOSSINVEST_UNAVAILABLE"],
      [500, "internal-error", "TOSSINVEST_UNAVAILABLE"],
      [503, "internal-error", "TOSSINVEST_UNAVAILABLE"],
    ] as const) {
      await session(async (fake, client) => {
        fake.fail("GET", "/api/v1/sectors/100", status, code);
        await expectFailure(client, expected, "get_sector", cases[2].args);
        expect(fake.seen("GET")).toHaveLength(1);
      });
    }
  });

  test("429는 한 번만 재송신하고 성공과 계속되는 실패를 구분한다", async () => {
    for (const recovered of [true, false]) {
      await session(async (fake, client) => {
        fake.sequence("GET", "/api/v1/sectors", [
          apiError(429, "rate-limit-exceeded"),
          recovered
            ? () => json(example("/api/v1/sectors", "sectors"))
            : apiError(429, "rate-limit-exceeded"),
        ]);
        if (recovered)
          expect((await tool(client, "list_sectors")).result.isError).not.toBe(
            true,
          );
        else
          await expectFailure(
            client,
            "TOSSINVEST_RATE_LIMITED",
            "list_sectors",
          );
        expect(fake.seen("GET")).toHaveLength(2);
        expect(fake.issued).toBe(1);
      });
    }
  }, 5000);

  test("HTTP timeout과 1 MiB를 넘는 본문을 거절한다", async () => {
    await session(async (fake, client) => {
      fake.routes.set("GET /api/v1/sectors", async () => {
        await Bun.sleep(100);
        return json(example("/api/v1/sectors", "sectors"));
      });
      await expectFailure(client, "TOSSINVEST_NETWORK", "list_sectors");
      expect(fake.seen("GET")).toHaveLength(1);
    }, 50);
    await session(async (fake, client) => {
      fake.on("GET", "/api/v1/sectors", {
        result: { sectors: [], padding: "x".repeat(1024 * 1024) },
      });
      await expectFailure(client, "TOSSINVEST_UNAVAILABLE", "list_sectors");
      expect(fake.seen("GET")).toHaveLength(1);
    });
  });

  test("잘못된 행이 있으면 정상 행만 남기지 않고 전체 오류를 반환한다", async () => {
    await session(async (fake, client) => {
      for (const item of cases) {
        const body = example(item.template, item.sample);
        if (item.name === "get_sector") delete body.result.performance;
        else {
          const rows =
            body.result[
              item.name === "list_sectors"
                ? "sectors"
                : item.name === "get_sector_rankings"
                  ? "rankings"
                  : item.name === "get_sector_stocks"
                    ? "stocks"
                    : "etfs"
            ];
          rows.push({ name: "필수 필드가 빠진 행" });
        }
        fake.on("GET", item.path, body);
        await expectFailure(
          client,
          "TOSSINVEST_UNAVAILABLE",
          item.name,
          item.args,
        );
      }
    });
  });

  test("SECTOR는 큐를 공유하고 SECTOR_RANKING은 독립 큐를 쓴다", async () => {
    await session(async (fake, client) => {
      const received = new Map<string, number>();
      for (const item of cases)
        fake.routes.set(`GET ${item.path}`, () => {
          received.set(item.path, performance.now());
          return json(example(item.template, item.sample));
        });
      await tool(client, "list_sectors");
      const start = received.get(cases[0].path)!;
      await Promise.all([
        tool(client, "get_sector", cases[2].args),
        tool(client, "get_sector_rankings", cases[1].args),
      ]);
      expect(received.get(cases[2].path)! - start).toBeGreaterThanOrEqual(190);
      expect(received.get(cases[1].path)! - start).toBeLessThan(190);
      expect(operationFor("GET", cases[2].path).group).toBe("SECTOR");
      expect(operationFor("GET", cases[1].path).group).toBe("SECTOR_RANKING");
    });
    await session(async (fake, client) => {
      fake.on("GET", "/api/v1/sectors", example("/api/v1/sectors", "sectors"));
      await tool(client, "list_sectors");
      await expectFailure(
        client,
        "TOSSINVEST_NETWORK",
        "get_sector",
        cases[2].args,
      );
      expect(fake.seen("GET", "/api/v1/sectors/100")).toHaveLength(0);
    }, 100);
  });
});
