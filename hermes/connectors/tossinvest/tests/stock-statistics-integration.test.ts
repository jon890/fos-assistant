import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { buildBundle } from "../scripts/build.ts";
import { createTossinvestServer } from "../src/server.ts";
import { FakeToss, credentials, tool, withMcp } from "./support.ts";

// 모듈의 등록 함수를 직접 부르지 않고 production 등록과 stdio를 독립적으로 검사한다.
const cases = [
  [
    "getStockInvestorTrading",
    "get_stock_investor_trading",
    "investor-trading",
    "NXT",
  ],
  [
    "getStockProgramTrades",
    "get_stock_program_trades",
    "program-trades",
    "arbitrage",
  ],
  ["getStockShortSelling", "get_stock_short_selling", "short-selling", "KRW"],
  [
    "getStockCreditTrades",
    "get_stock_credit_trades",
    "credit-trades",
    "marginLoan",
  ],
  [
    "getStockSecuritiesLending",
    "get_stock_securities_lending",
    "securities-lending",
    "기관",
  ],
] as const;
const allowed = new Set(["issueOAuth2Token", ...cases.map(([id]) => id)]);
const canonical = JSON.parse(
  readFileSync(`${import.meta.dir}/fixtures/openapi-1.2.24.json`, "utf8"),
);
const manifest = JSON.parse(
  readFileSync(`${import.meta.dir}/../connector.json`, "utf8"),
);

async function checkProduction(client: Client, fake: FakeToss) {
  const listed = (await client.listTools()).tools;
  // READ 33개와 주문 준비 READ 하나, 실행 FINANCIAL 둘이다.
  expect(listed).toHaveLength(36);
  expect(new Set(listed.map(({ name }) => name))).toEqual(
    new Set(Object.keys(manifest.tools)),
  );
  expect(fake.requests).toHaveLength(0);
  for (const item of listed) {
    expect(item.description).toBeDefined();
    expect(item.description).not.toContain("undefined");
    expect(item.annotations?.readOnlyHint).toBe(
      manifest.tools[item.name].risk === "READ",
    );
  }
  expect(
    listed
      .filter((item) => !item.annotations?.readOnlyHint)
      .map((item) => item.name)
      .sort(),
  ).toEqual(["cancel_order", "create_order"]);
  expect(manifest.schema).toBe(2);
  for (const [, name, , detail] of cases) {
    const registered = listed.find((item) => item.name === name)!;
    expect(listed.filter((item) => item.name === name)).toHaveLength(1);
    expect(manifest.tools[name]).toEqual({
      risk: "READ",
      approval: "none",
      title: expect.any(String),
      summary: expect.any(String),
      description: registered.description,
    });
    expect(registered.description).toContain(detail);
    for (const term of [
      "KR",
      "symbol",
      "10",
      "100",
      "until",
      "nextUntil",
      "null",
    ])
      expect(registered.description).toContain(term);
    expect(registered.inputSchema.additionalProperties).toBe(false);
    expect(registered.inputSchema.required).toContain("symbol");
    expect(registered.inputSchema.properties?.count).toMatchObject({
      default: 10,
      minimum: 1,
      maximum: 100,
    });
    expect(registered.inputSchema.properties?.until).toMatchObject({
      format: "date",
    });
    for (const args of [
      {},
      { symbol: "." },
      { symbol: ".." },
      { symbol: "A/other" },
      { symbol: "https://example.invalid" },
      { symbol: "A?count=100" },
      { symbol: "A\n" },
      { symbol: "A", count: 101 },
      { symbol: "A", until: "2026-02-30" },
      ...[
        "account",
        "account_seq",
        "accountSeq",
        "token",
        "access_token",
        "url",
        "apiBase",
        "extra",
      ].map((key) => ({ symbol: "A", [key]: "caller-injection" })),
    ])
      expect((await client.callTool({ name, arguments: args })).isError).toBe(
        true,
      );
  }
  // 유효한 입력을 보내기 전에 인증과 GET 모두 0회인지 단언한다.
  expect(fake.seen("POST", "/oauth2/token")).toHaveLength(0);
  expect(fake.requests).toHaveLength(0);

  for (const [, name, segment] of cases) {
    const upstream = canonical.paths[`/api/v1/stocks/{symbol}/${segment}`].get;
    const example =
      upstream.responses["200"].content["application/json"].examples.daily
        .value;
    fake.on("GET", `/api/v1/stocks/A.TEST-1/${segment}`, example);
    const first = await tool(client, name, { symbol: "A.TEST-1" });
    expect(first.result.isError).toBeUndefined();
    expect(first.body).toEqual({ result: example.result, metadata: {} });
    expect(first.result.structuredContent).toEqual(first.body);
    const request = fake.seen("GET").at(-1)!;
    expect(request.path).toBe(`/api/v1/stocks/A.TEST-1/${segment}`);
    expect(Object.fromEntries(request.query)).toEqual({ count: "10" });
    fake.on("GET", request.path, { result: { records: [], nextUntil: null } });
    const last = await tool(client, name, {
      symbol: "A.TEST-1",
      count: 100,
      until: "2026-07-17",
    });
    expect(last.result.isError).toBeUndefined();
    expect(last.body).toEqual({
      result: { records: [], nextUntil: null },
      metadata: {},
    });
    expect(Object.fromEntries(fake.seen("GET").at(-1)!.query)).toEqual({
      count: "100",
      until: "2026-07-17",
    });
  }
  expect(fake.seen("GET")).toHaveLength(10);
  for (const [, , segment] of cases)
    expect(fake.seen("GET", `/api/v1/stocks/A.TEST-1/${segment}`)).toHaveLength(
      2,
    );
  for (const request of fake.seen("GET")) {
    expect(request.body).toBe("");
    expect(request.headers.get("x-tossinvest-account")).toBeNull();
    expect(request.headers.get("authorization")).toBe(
      "Bearer fake-access-token-1",
    );
  }
  expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
  expect(fake.seen("POST")).toHaveLength(1);
  const auth = fake.seen("POST")[0]!;
  expect(auth.path).toBe("/oauth2/token");
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
      ({ method, path }) => method === "POST" && path !== "/oauth2/token",
    ),
  ).toHaveLength(0);
  expect(fake.seen("DELETE")).toHaveLength(0);
}

test("production SDK의 통계 5개 등록, strict 입력과 모든 GET, 인증과 금융 경계를 확인한다", async () => {
  const fake = new FakeToss(allowed);
  try {
    await withMcp(
      createTossinvestServer({
        apiBase: fake.url,
        env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "7" },
      }),
      (client) => checkProduction(client, fake),
    );
  } finally {
    fake.stop();
  }
});

test("production 통계의 자격 증명 부재는 인증을 포함해 HTTP 0회다", async () => {
  const fake = new FakeToss(allowed);
  try {
    await withMcp(
      createTossinvestServer({ apiBase: fake.url, env: {} }),
      async (client) => {
        for (const [, name] of cases) {
          const failed = await tool(client, name, { symbol: "005930" });
          expect(failed.result.isError).toBe(true);
          expect(failed.body).toEqual({
            error: { code: "TOSSINVEST_UNAUTHORIZED" },
          });
        }
      },
    );
    expect(fake.seen("POST", "/oauth2/token")).toHaveLength(0);
    expect(fake.requests).toHaveLength(0);
  } finally {
    fake.stop();
  }
});

test("standalone stdio bundle에서도 최종 설명과 통계 5개의 실제 HTTP 경계가 같다", async () => {
  const fake = new FakeToss(allowed);
  const directory = await mkdtemp(join(tmpdir(), "stock-statistics-stdio-"));
  const client = new Client({ name: "statistics-stdio-test", version: "1.0" });
  try {
    await buildBundle(directory);
    const preload = join(directory, "loopback.ts");
    // production origin은 고정하고 시험 프로세스의 전송만 loopback으로 바꾼다.
    await writeFile(
      preload,
      `const original = globalThis.fetch; globalThis.fetch = ((input, init) => { const url = String(input); if (!url.startsWith('https://openapi.tossinvest.com/')) throw new Error('unexpected-origin'); return original(url.replace('https://openapi.tossinvest.com', ${JSON.stringify(fake.url)}), init); });`,
    );
    await client.connect(
      new StdioClientTransport({
        command: process.execPath,
        args: ["--preload", preload, join(directory, "tossinvest-mcp.js")],
        env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "7" },
        stderr: "pipe",
      }),
    );
    await checkProduction(client, fake);
  } finally {
    await client.close();
    fake.stop();
    await rm(directory, { recursive: true, force: true });
  }
});
