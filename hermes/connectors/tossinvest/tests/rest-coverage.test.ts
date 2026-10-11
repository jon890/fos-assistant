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
import manifest from "../connector.json";
import {
  FakeToss,
  emptyHoldings,
  credentials,
  expectFailure,
  tool,
  withMcp,
} from "./support.ts";

const raw = readFileSync(`${import.meta.dir}/fixtures/openapi-1.2.24.json`);
export const canonical = JSON.parse(raw.toString());

const coverageCalls = [
  ["list_accounts", {}],
  ["get_quotes", { symbols: "AAPL" }],
  ["get_holdings", {}],
  ["get_buying_power", { currency: "KRW", symbol: "AAPL" }],
  ["list_orders", { status: "OPEN" }],
  ["get_sellable_quantity", { symbol: "AAPL" }],
  ["list_sectors", {}],
  [
    "get_sector_rankings",
    { type: "TOP_GAINERS", marketCountry: "KR", duration: "1d" },
  ],
  ["get_sector", { sectorId: "100", marketCountry: "KR" }],
  ["get_sector_stocks", { sectorId: "100", marketCountry: "US" }],
  ["get_sector_etfs", { sectorId: "100", marketCountry: "KR" }],
] as const;

const coverageOperations = new Set([
  "issueOAuth2Token",
  "getAccounts",
  "getPrices",
  "getStocks",
  "getHoldings",
  "getBuyingPower",
  "getSellableQuantity",
  "getOrders",
  "listSectors",
  "getSectorRankings",
  "getSector",
  "getSectorStocks",
  "getSectorEtfs",
]);

function installCoverageRoutes(fake: FakeToss) {
  for (const [path, result] of Object.entries({
    accounts: [],
    prices: [],
    stocks: [],
    holdings: emptyHoldings,
    "buying-power": { currency: "KRW", cashBuyingPower: "1" },
    "sellable-quantity": { sellableQuantity: "9007199254740993.001" },
    orders: { orders: [], nextCursor: null, hasNext: false },
  }))
    fake.on("GET", `/api/v1/${path}`, { result });
  for (const [template, sample] of [
    ["/api/v1/sectors", "sectors"],
    ["/api/v1/sectors/rankings", "topGainers"],
    ["/api/v1/sectors/{sectorId}", "sector"],
    ["/api/v1/sectors/{sectorId}/stocks", "usStocks"],
    ["/api/v1/sectors/{sectorId}/etfs", "krEtfs"],
  ])
    fake.on(
      "GET",
      template!.replace("{sectorId}", "100"),
      canonical.paths[template!].get.responses["200"].content[
        "application/json"
      ].examples[sample!].value,
    );
}

function expectCoverageRequests(fake: FakeToss, accountSeq = "7") {
  const reached = new Set(
    fake.requests.map(
      (request) =>
        OPERATIONS.find((op) =>
          matchesOperation(op, request.method, request.path),
        )!.id,
    ),
  );
  expect(reached).toEqual(coverageOperations);
  expect(CURRENT_OPERATIONS).toEqual(reached);
  expect(fake.seen("GET")).toHaveLength(13);
  expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
  expect(
    fake.requests.filter(
      (request) =>
        ["POST", "DELETE"].includes(request.method) &&
        request.path !== "/oauth2/token",
    ),
  ).toHaveLength(0);
  for (const request of fake.seen("GET")) {
    expect(request.body).toBe("");
    expect(request.headers.get("authorization")).toBe(
      "Bearer fake-access-token-1",
    );
    expect(request.headers.get("x-tossinvest-account")).toBe(
      request.path.startsWith("/api/v1/sectors") ||
        ["accounts", "prices", "stocks"].some(
          (path) => request.path === `/api/v1/${path}`,
        )
        ? null
        : accountSeq,
    );
    if (request.path.startsWith("/api/v1/sectors")) {
      expect(Object.fromEntries(request.query)).toEqual(
        request.path.endsWith("/rankings")
          ? { type: "TOP_GAINERS", marketCountry: "KR", duration: "1d" }
          : request.path === "/api/v1/sectors"
            ? {}
            : { marketCountry: request.path.endsWith("/stocks") ? "US" : "KR" },
      );
    }
  }
}

async function expectCatalog(client: Client) {
  const tools = (await client.listTools()).tools;
  expect(new Set(tools.map((item) => item.name))).toEqual(
    new Set(coverageCalls.map(([name]) => name)),
  );
  expect(new Set(Object.keys(manifest.tools))).toEqual(
    new Set(tools.map((item) => item.name)),
  );
  expect(manifest.default_tool_policy).toBe("deny");
  for (const item of tools) {
    const declared = (
      manifest.tools as Record<string, { risk: string; description: string }>
    )[item.name]!;
    expect(declared.risk).toBe("READ");
    expect(item.annotations?.readOnlyHint).toBe(true);
    expect(item.description).toBe(declared.description);
    expect(item.description).toBe(item.description!.trim());
    expect(Array.from(item.description!).length).toBeLessThanOrEqual(2000);
    expect(
      new TextEncoder().encode(item.description).length,
    ).toBeLessThanOrEqual(8192);
    expect(item.description).not.toMatch(
      /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069\ud800-\udfff]/u,
    );
    expect(item.inputSchema.additionalProperties).toBe(false);
  }
}

test("현재 12개 READ operation의 공식 성공 예시 30개를 그대로 읽는다", () => {
  const reads = new Set([
    "getAccounts",
    "getPrices",
    "getStocks",
    "getHoldings",
    "getBuyingPower",
    "getSellableQuantity",
    "getOrders",
    "listSectors",
    "getSectorRankings",
    "getSector",
    "getSectorStocks",
    "getSectorEtfs",
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
  expect(count).toBe(30);
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
  expect(CURRENT_OPERATIONS.size).toBe(13);
  expect(
    OPERATIONS.filter((op) => !CURRENT_OPERATIONS.has(op.id)),
  ).toHaveLength(28);
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

test("SDK의 기존 6개와 섹터 5개 도구가 실제 HTTP operation 합집합에 도달한다", async () => {
  const fake = new FakeToss(coverageOperations);
  installCoverageRoutes(fake);
  try {
    await withMcp(
      createTossinvestServer({
        apiBase: fake.url,
        env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "7" },
      }),
      async (client) => {
        await expectCatalog(client);
        for (const [name, args] of coverageCalls)
          expect(
            (await tool(client, name, args)).result.isError,
          ).toBeUndefined();
      },
    );
    expectCoverageRequests(fake);
    const token = fake.seen("POST", "/oauth2/token")[0]!;
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

test("운영 서버도 공식 섹터 404만 입력 오류로 알리고 원문은 숨긴다", async () => {
  const fake = new FakeToss(coverageOperations);
  try {
    await withMcp(
      createTossinvestServer({ apiBase: fake.url, env: credentials }),
      async (client) => {
        const official =
          canonical.components.responses.SectorNotFound.content[
            "application/json"
          ].example;
        for (const [name, suffix] of [
          ["get_sector", ""],
          ["get_sector_stocks", "/stocks"],
          ["get_sector_etfs", "/etfs"],
        ] as const) {
          fake.on("GET", `/api/v1/sectors/missing${suffix}`, official, 404);
          await expectFailure(client, "TOSSINVEST_INVALID_INPUT", name, {
            sectorId: "missing",
            marketCountry: "KR",
          });
        }
        for (const [status, code] of [
          [404, "not-found"],
          [500, "sector-not-found"],
        ] as const) {
          fake.fail("GET", "/api/v1/sectors/missing", status, code);
          await expectFailure(client, "TOSSINVEST_UNAVAILABLE", "get_sector", {
            sectorId: "missing",
            marketCountry: "US",
          });
        }
        expect(fake.seen("GET")).toHaveLength(5);
        expect(fake.issued).toBe(1);
      },
    );
  } finally {
    fake.stop();
  }
});

test("독립 stdio bundle의 11개 도구 설명과 13개 operation을 실제 HTTP로 확인한다", async () => {
  const fake = new FakeToss(coverageOperations);
  const directory = await mkdtemp(join(tmpdir(), "toss-rest-bundle-"));
  const preload = join(directory, "loopback.ts");
  const client = new Client({ name: "bundle-test", version: "1.0" });
  installCoverageRoutes(fake);
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
    await expectCatalog(client);
    for (const [name, args] of coverageCalls) {
      const { result, body } = await tool(client, name, args);
      expect(result.isError).toBeUndefined();
      expect(result.structuredContent).toEqual(body);
      if (name === "get_sellable_quantity") {
        expect(body.result).toEqual({
          sellableQuantity: "9007199254740993.001",
        });
        expect(body.sellable_quantity).toBe("9007199254740993.001");
      }
      if (name.startsWith("get_sector") || name === "list_sectors") {
        const path =
          name === "list_sectors"
            ? "/api/v1/sectors"
            : name === "get_sector_rankings"
              ? "/api/v1/sectors/rankings"
              : `/api/v1/sectors/100${name === "get_sector_stocks" ? "/stocks" : name === "get_sector_etfs" ? "/etfs" : ""}`;
        const route = fake.routes.get(`GET ${path}`) as Response;
        expect(body).toEqual({
          result: (await route.clone().json()).result,
          metadata: {},
        });
      }
    }
    expectCoverageRequests(fake, "9223372036854775807");
  } finally {
    await client.close();
    fake.stop();
    await rm(directory, { recursive: true, force: true });
  }
});
