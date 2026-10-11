import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { buildBundle } from "../scripts/build.ts";
import { OPERATIONS, matchesOperation } from "../src/api-contract.ts";
import { proxyFreeEnvironment } from "../src/runtime.ts";
import { FakeToss, credentials, expectFailure, json, tool } from "./support.ts";

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
  ["/api/v1/orderbook", "get_orderbook", { symbol: "005930" }],
  ["/api/v1/trades", "get_trades", { symbol: "AAPL" }],
  ["/api/v1/price-limits", "get_price_limits", { symbol: "AAPL" }],
  ["/api/v1/candles", "get_candles", { symbol: "005930", interval: "1d" }],
] as const;
const manifest = JSON.parse(
  readFileSync(`${import.meta.dir}/../connector.json`, "utf8"),
);

test("빌드한 stdio 서버는 SDK와 raw JSON-RPC에서 시세·시장 7 operation을 계좌 없이 실제 HTTP로 보낸다", async () => {
  const allowed = new Set([
    "issueOAuth2Token",
    "getExchangeRate",
    "getKrMarketCalendar",
    "getUsMarketCalendar",
    "getOrderbook",
    "getTrades",
    "getPriceLimit",
    "getCandles",
  ]);
  const fake = new FakeToss(allowed);
  const directory = await mkdtemp(join(tmpdir(), "toss-market-stdio-"));
  const preload = join(directory, "loopback.ts");
  const bundle = join(directory, "tossinvest-mcp.js");
  const client = new Client({ name: "market-stdio-test", version: "1.0" });
  const responses = cases.map(
    ([path]) =>
      Object.values(
        canonical.paths[path].get.responses["200"].content["application/json"]
          .examples as Record<string, { value: any }>,
      )[0]!.value,
  );
  const missingRate =
    canonical.paths[cases[0][0]].get.responses["404"].content[
      "application/json"
    ].examples.exchangeRateNotFound.value;
  const historical = { ...cases[0][2], dateTime: "2020-01-01T00:00:00Z" };
  for (const [i, [path]] of cases.entries())
    fake.routes.set(`GET ${path}`, (request) =>
      request.query.get("dateTime") === historical.dateTime
        ? json(missingRate, 404)
        : json({
            ...responses[i],
            metadata: { upstream: "노출하지 않을 확장 필드" },
          }),
    );
  try {
    await buildBundle(directory);
    // production origin은 유지하고 시험 프로세스에서만 HTTP 목적지를 바꾼다.
    await writeFile(
      preload,
      `const original = globalThis.fetch; globalThis.fetch = ((input, init) => { const url = String(input); if (!url.startsWith('https://openapi.tossinvest.com/')) throw new Error('unexpected-origin'); return original(url.replace('https://openapi.tossinvest.com', ${JSON.stringify(fake.url)}), init); });`,
    );
    await client.connect(
      new StdioClientTransport({
        command: process.execPath,
        args: ["--preload", preload, bundle],
        env: credentials,
        stderr: "pipe",
      }),
    );
    const tools = (await client.listTools()).tools;
    expect(tools.map((t) => t.name).sort()).toEqual(
      Object.keys(manifest.tools).sort(),
    );
    expect(tools).toHaveLength(15);
    for (const [i, [, name, args]] of cases.entries()) {
      const definition = tools.find((t) => t.name === name)!;
      expect(definition.annotations?.readOnlyHint).toBe(true);
      expect(definition.description).toBeTruthy();
      if (i < 3) expect(definition.description).toContain("metadata");
      expect(manifest.tools[name]).toEqual({
        risk: "READ",
        description: definition.description,
      });
      const { result, body } = await tool(client, name, args);
      expect(result.isError).toBeUndefined();
      expect(body).toEqual({ result: responses[i].result, metadata: {} });
      expect(result.structuredContent).toEqual(body);
    }
    await expectFailure(
      client,
      "TOSSINVEST_INVALID_INPUT",
      "get_exchange_rate",
      historical,
    );
    const beforeInvalid = fake.requests.length;
    for (const dateTime of ["2026-03-25T09:30Z", "2026-03-25T09:30+09:00"])
      expect(
        (
          await client.callTool({
            name: "get_exchange_rate",
            arguments: { ...cases[0][2], dateTime },
          })
        ).isError,
      ).toBe(true);
    expect(fake.requests).toHaveLength(beforeInvalid);
    await client.close();
    expect(fake.requests).toHaveLength(9);

    // 한 프로세스의 줄 단위 trace를 직접 읽어 SDK가 감추는 stdout도 확인한다.
    const messages = [
      {
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: "2024-11-05",
          capabilities: {},
          clientInfo: { name: "raw-trace", version: "1" },
        },
      },
      { jsonrpc: "2.0", method: "notifications/initialized" },
      { jsonrpc: "2.0", id: 2, method: "tools/list", params: {} },
      ...cases.map(([, name, args], i) => ({
        jsonrpc: "2.0",
        id: i + 3,
        method: "tools/call",
        params: { name, arguments: args },
      })),
      {
        jsonrpc: "2.0",
        id: 10,
        method: "tools/call",
        params: {
          name: "get_market_calendar",
          arguments: { market: "KR", date: "2026-02-29" },
        },
      },
      {
        jsonrpc: "2.0",
        id: 11,
        method: "tools/call",
        params: { name: "get_exchange_rate", arguments: historical },
      },
      ...["2026-03-25T09:30Z", "2026-03-25T09:30+09:00"].map((dateTime, i) => ({
        jsonrpc: "2.0",
        id: i + 12,
        method: "tools/call",
        params: {
          name: "get_exchange_rate",
          arguments: { ...cases[0][2], dateTime },
        },
      })),
    ];
    const child = Bun.spawn([process.execPath, "--preload", preload, bundle], {
      env: proxyFreeEnvironment({ ...process.env, ...credentials }),
      stdin: "pipe",
      stdout: "pipe",
      stderr: "pipe",
    });
    child.stdin.write(messages.map((m) => JSON.stringify(m)).join("\n") + "\n");
    const reader = child.stdout.getReader();
    let trace = "";
    const deadline = setTimeout(() => child.kill(), 10000);
    try {
      while (
        !Array.from({ length: 13 }, (_, i) => i + 1).every((id) =>
          trace
            .split("\n")
            .slice(0, -1)
            .some((line) => line && JSON.parse(line).id === id),
        )
      ) {
        const { done, value } = await reader.read();
        if (done) throw new Error("stdio trace가 응답 전에 끝났다");
        trace += new TextDecoder().decode(value);
        // 다음 줄이 잘린 경우에는 완성된 줄만 판정한다.
        if (!trace.endsWith("\n")) continue;
      }
      const replies = trace
        .trim()
        .split("\n")
        .map((line) => JSON.parse(line));
      expect(replies.every((r) => r.jsonrpc === "2.0")).toBe(true);
      expect(
        replies
          .find((r) => r.id === 2)
          .result.tools.map((t: any) => t.name)
          .sort(),
      ).toEqual(Object.keys(manifest.tools).sort());
      for (const [i] of cases.entries()) {
        const result = replies.find((r) => r.id === i + 3).result;
        expect(result.isError).toBeUndefined();
        expect(JSON.parse(result.content[0].text)).toEqual({
          result: responses[i].result,
          metadata: {},
        });
      }
      expect(replies.find((r) => r.id === 10).result.isError).toBe(true);
      const notFound = replies.find((r) => r.id === 11).result;
      expect(notFound.isError).toBe(true);
      expect(JSON.parse(notFound.content[0].text)).toEqual({
        error: { code: "TOSSINVEST_INVALID_INPUT" },
      });
      for (const id of [12, 13])
        expect(replies.find((r) => r.id === id).result.isError).toBe(true);
    } finally {
      clearTimeout(deadline);
      child.stdin.end();
      child.kill();
      await child.exited;
    }
    expect(fake.requests).toHaveLength(18);
    expect(
      new Set(
        fake.requests.map(
          (request) =>
            OPERATIONS.find((op) =>
              matchesOperation(op, request.method, request.path),
            )!.id,
        ),
      ),
    ).toEqual(allowed);
    // raw JSON-RPC는 여러 그룹을 함께 호출하므로 그룹 사이 도착 순서는 정하지 않는다.
    expect(
      fake
        .seen("GET")
        .map((r) => r.path)
        .sort(),
    ).toEqual(
      [...cases, cases[0], ...cases, cases[0]].map(([path]) => path).sort(),
    );
    expect(fake.seen("POST", "/oauth2/token")).toHaveLength(2);
    for (const [i, request] of fake.seen("GET").entries()) {
      expect(request.body).toBe("");
      expect(request.headers.get("x-tossinvest-account")).toBeNull();
      expect(request.headers.get("authorization")).toBe(
        `Bearer fake-access-token-${i < cases.length + 1 ? 1 : 2}`,
      );
    }
  } finally {
    await client.close();
    fake.stop();
    await rm(directory, { recursive: true, force: true });
  }
});
