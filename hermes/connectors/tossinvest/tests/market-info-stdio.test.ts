import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { buildBundle } from "../scripts/build.ts";
import { proxyFreeEnvironment } from "../src/runtime.ts";
import { FakeToss, credentials, tool } from "./support.ts";

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
const manifest = JSON.parse(
  readFileSync(`${import.meta.dir}/../connector.json`, "utf8"),
);

test("빌드한 stdio 서버는 SDK와 raw JSON-RPC에서 새 3 operation을 실제 HTTP로 보낸다", async () => {
  const fake = new FakeToss(
    new Set([
      "issueOAuth2Token",
      "getExchangeRate",
      "getKrMarketCalendar",
      "getUsMarketCalendar",
    ]),
  );
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
  for (const [i, [path]] of cases.entries()) fake.on("GET", path, responses[i]);
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
    expect(tools).toHaveLength(8);
    for (const [i, [, name, args]] of cases.entries()) {
      const definition = tools.find((t) => t.name === name)!;
      expect(definition.annotations?.readOnlyHint).toBe(true);
      expect(definition.description).toContain("metadata");
      expect(manifest.tools[name]).toEqual({ risk: "READ" });
      const { result, body } = await tool(client, name, args);
      expect(result.isError).toBeUndefined();
      expect(body).toEqual({ result: responses[i].result, metadata: {} });
      expect(result.structuredContent).toEqual(body);
    }
    await client.close();
    expect(fake.requests).toHaveLength(4);

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
        id: 6,
        method: "tools/call",
        params: {
          name: "get_market_calendar",
          arguments: { market: "KR", date: "2026-02-29" },
        },
      },
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
        ![1, 2, 3, 4, 5, 6].every((id) =>
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
      expect(replies.find((r) => r.id === 6).result.isError).toBe(true);
    } finally {
      clearTimeout(deadline);
      child.stdin.end();
      child.kill();
      await child.exited;
    }
    expect(fake.requests).toHaveLength(8);
    expect(fake.seen("GET").map((r) => r.path)).toEqual(
      [...cases, ...cases].map(([path]) => path),
    );
    for (const request of fake.seen("GET")) {
      expect(request.body).toBe("");
      expect(request.headers.get("x-tossinvest-account")).toBeNull();
    }
  } finally {
    await client.close();
    fake.stop();
    await rm(directory, { recursive: true, force: true });
  }
});
