import { readToolNames, readOperationIds } from "./read-union-cases.ts";
import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { buildBundle } from "../scripts/build.ts";
import {
  CURRENT_OPERATIONS,
  OPERATIONS,
  matchesOperation,
} from "../src/api-contract.ts";
import { FakeToss, credentials, tool } from "./support.ts";

const canonical = JSON.parse(
  readFileSync(`${import.meta.dir}/fixtures/openapi-1.2.24.json`, "utf8"),
);
const connector = JSON.parse(
  readFileSync(`${import.meta.dir}/../connector.json`, "utf8"),
);
const calls = [
  [
    "get_rankings",
    "getRankings",
    { type: "TOP_GAINERS", marketCountry: "KR", duration: "1d" },
    "/api/v1/rankings",
  ],
  [
    "get_market_indicator_prices",
    "getMarketIndicatorPrices",
    { symbols: "KOSPI" },
    "/api/v1/market-indicators/prices",
  ],
  [
    "get_market_indicator_candles",
    "getMarketIndicatorCandles",
    { symbol: "KOSPI", interval: "1d" },
    "/api/v1/market-indicators/KOSPI/candles",
  ],
  [
    "get_market_indicator_investor_trading",
    "getMarketIndicatorInvestorTrading",
    { symbol: "KOSPI", interval: "1d" },
    "/api/v1/market-indicators/KOSPI/investor-trading",
  ],
] as const;

test("production stdio 도구 합집합의 설명·READ 정책과 신규 GET 4개 도달을 확인한다", async () => {
  expect(CURRENT_OPERATIONS).toEqual(
    new Set(["issueOAuth2Token", ...readOperationIds]),
  );
  const fake = new FakeToss(
    new Set(["issueOAuth2Token", ...calls.map(([, operation]) => operation)]),
  );
  const directory = await mkdtemp(join(tmpdir(), "ranking-production-"));
  const client = new Client({ name: "ranking-production", version: "1.0" });
  const examples = new Map<string, any>();
  for (const [name, id, , path] of calls) {
    const operation = OPERATIONS.find((op) => op.id === id)!;
    const content =
      canonical.paths[operation.path].get.responses["200"].content[
        "application/json"
      ];
    const example =
      content.example ?? (Object.values(content.examples)[0] as any).value;
    examples.set(name, example);
    fake.on("GET", path, example);
  }
  try {
    await buildBundle(directory);
    const preload = join(directory, "loopback.ts");
    // production origin을 유지하고 시험 프로세스의 전송만 loopback으로 보낸다.
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
    const listed = (await client.listTools()).tools;
    expect(connector.schema).toBe(2);
    expect(new Set(listed.map((entry) => entry.name))).toEqual(
      new Set(Object.keys(connector.tools)),
    );
    // READ 33개는 그대로이고 주문 준비 READ 하나와 실행 FINANCIAL 둘이 따로 있다.
    const financial = ["cancel_order", "create_order"];
    expect(
      new Set(
        listed
          .map((entry) => entry.name)
          .filter(
            (name) => !financial.includes(name) && name !== "prepare_trade",
          ),
      ),
    ).toEqual(new Set(readToolNames));
    expect(listed).toHaveLength(36);
    for (const entry of listed) {
      const read = !financial.includes(entry.name);
      expect(entry.description).toBeTruthy();
      expect(entry.description).not.toContain("undefined");
      expect(entry.annotations?.readOnlyHint).toBe(read);
      expect(connector.tools[entry.name].risk).toBe(
        read ? "READ" : "FINANCIAL",
      );
      expect(entry.description).toBe(connector.tools[entry.name].description);
    }
    expect(
      listed.find((entry) => entry.name === "get_market_indicator_prices")!
        .description,
    ).toContain("percent");
    expect(
      listed.find((entry) => entry.name === "get_market_indicator_candles")!
        .description,
    ).toContain("nextBefore");
    expect(
      listed.find(
        (entry) => entry.name === "get_market_indicator_investor_trading",
      )!.description,
    ).toContain("nextUntil");
    for (const [name, , args] of calls) {
      for (const key of ["account", "accountSeq", "token", "url", "apiBase"]) {
        const rejected = await client.callTool({
          name,
          arguments: { ...args, [key]: "caller-value" },
        });
        expect(rejected.isError).toBe(true);
      }
    }
    expect(fake.requests).toHaveLength(0);
    for (const [name, , args, path] of calls) {
      const { result, body } = await tool(client, name, args);
      expect(result.isError).toBeUndefined();
      expect(body.result).toEqual(examples.get(name).result);
      expect(result.structuredContent).toEqual(body);
      expect(fake.seen("GET", path)).toHaveLength(1);
    }
    expect(
      new Set(
        fake
          .seen("GET")
          .map(
            (request) =>
              OPERATIONS.find((op) =>
                matchesOperation(op, request.method, request.path),
              )!.id,
          ),
      ),
    ).toEqual(new Set(calls.map(([, id]) => id)));
    expect(fake.seen("POST", "/oauth2/token")).toHaveLength(1);
    expect(
      fake.seen("POST").filter((request) => request.path !== "/oauth2/token"),
    ).toHaveLength(0);
    expect(fake.seen("DELETE")).toHaveLength(0);
    expect(fake.requests).toHaveLength(5);
    for (const request of fake.seen("GET")) {
      expect(request.body).toBe("");
      expect(request.headers.get("x-tossinvest-account")).toBeNull();
      expect(request.headers.get("authorization")).toBe(
        "Bearer fake-access-token-1",
      );
    }
  } finally {
    await client.close();
    fake.stop();
    await rm(directory, { recursive: true, force: true });
  }
});
