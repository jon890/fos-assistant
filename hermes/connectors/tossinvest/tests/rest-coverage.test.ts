import { expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { buildBundle } from "../scripts/build.ts";
import { OPERATIONS, CURRENT_OPERATIONS, CONTRACT_SHA256, matchesOperation } from "../src/api-contract.ts";
import { createTossinvestServer } from "../src/server.ts";
import { FakeToss, emptyHoldings, credentials, tool, withMcp } from "./support.ts";

const raw = readFileSync(`${import.meta.dir}/fixtures/openapi-1.2.24.json`);
export const canonical = JSON.parse(raw.toString());

test("공식 41 operation의 method/path/group과 SHA-256이 일치하고 미구현을 구분한다", () => {
  expect(canonical.info.version).toBe("1.2.24");
  expect(createHash("sha256").update(raw).digest("hex")).toBe(CONTRACT_SHA256);
  const expected = Object.entries(canonical.paths).flatMap(([path, methods]: [string, any]) => Object.entries(methods).filter(([, op]: any) => op.operationId).map(([method, op]: any) => ({ id: op.operationId, method: method.toUpperCase(), path, group: /Rate Limits Group[^`]*`([^`]+)/.exec(op.description)![1] })));
  expect(OPERATIONS.map(({ id, method, path, group }) => ({ id, method, path, group }))).toEqual(expected);
  expect(new Set(OPERATIONS.map(op => `${op.method} ${op.path}`)).size).toBe(41);
  expect(new Set(OPERATIONS.map(op => op.group)).size).toBe(19);
  expect(CURRENT_OPERATIONS.size).toBe(8);
  expect(OPERATIONS.filter(op => !CURRENT_OPERATIONS.has(op.id))).toHaveLength(33);
});

test("READ 허용 집합은 금융 POST와 DELETE를 거절한다", async () => {
  for (const [method, path] of [["POST", "/api/v1/orders"], ["DELETE", "/api/v1/conditional-orders/id"]]) {
    const fake = new FakeToss(new Set(["issueOAuth2Token", "getAccounts"]));
    fake.on(method!, path!, {});
    await fetch(`${fake.url}${path}`, { method });
    expect(() => fake.stop()).toThrow();
  }
  const op = OPERATIONS.find(op => op.id === "cancelConditionalOrder")!;
  expect(matchesOperation(op, "DELETE", "/api/v1/conditional-orders/id%2Fone")).toBe(true);
  expect(matchesOperation(op, "DELETE", "/api/v1/conditional-orders/id/one")).toBe(false);
});

test("실제 SDK 도구 호출로 현재 8 operation에 도달하고 GET body는 없다", async () => {
  const fake = new FakeToss();
  for (const [path, result] of Object.entries({ accounts: [], prices: [], stocks: [], holdings: emptyHoldings, "buying-power": { currency: "KRW", cashBuyingPower: "1" }, "sellable-quantity": { sellableQuantity: "2" }, orders: { orders: [], nextCursor: null, hasNext: false } })) fake.on("GET", `/api/v1/${path}`, { result });
  try {
    await withMcp(createTossinvestServer({ apiBase: fake.url, env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "7" } }), async client => {
      for (const [name, args] of [["list_accounts", {}], ["get_quotes", { symbols: "AAPL" }], ["get_holdings", {}], ["get_buying_power", { currency: "KRW", symbol: "AAPL" }], ["list_orders", { status: "OPEN" }]] as const) expect((await tool(client, name, args)).result.isError).toBeUndefined();
    });
    expect(new Set(fake.requests.map(request => OPERATIONS.find(op => matchesOperation(op, request.method, request.path))!.id))).toEqual(CURRENT_OPERATIONS);
    expect(fake.requests).toHaveLength(8);
    for (const request of fake.seen("GET")) {
      expect(request.body).toBe("");
      expect(request.headers.get("authorization")).toBe("Bearer fake-access-token-1");
      expect(request.headers.get("x-tossinvest-account")).toBe(["accounts", "prices", "stocks"].some(path => request.path.endsWith(`/${path}`)) ? null : "7");
    }
    const token = fake.seen("POST")[0]!;
    expect(Object.fromEntries(new URLSearchParams(token.body))).toEqual({ grant_type: "client_credentials", client_id: credentials.TOSSINVEST_CLIENT_ID, client_secret: credentials.TOSSINVEST_CLIENT_SECRET });
  } finally { fake.stop(); }
});

test("고정 fixture에서 생성한 계약은 재현 가능하다", async () => {
  const child = Bun.spawn([process.execPath, `${import.meta.dir}/../scripts/generate-contract.ts`, "--check"], { stdout: "pipe", stderr: "pipe" });
  expect(await child.exited).toBe(0);
  expect(await new Response(child.stderr).text()).toBe("");
});

test("빌드한 stdio bundle의 새 도구는 실제 loopback HTTP 목적지에 한 번 도달한다", async () => {
  const fake = new FakeToss(new Set(["issueOAuth2Token", "getSellableQuantity"]));
  const directory = await mkdtemp(join(tmpdir(), "toss-rest-bundle-"));
  const preload = join(directory, "loopback.ts");
  const client = new Client({ name: "bundle-test", version: "1.0" });
  fake.on("GET", "/api/v1/sellable-quantity", { result: { sellableQuantity: "9007199254740993.001" } });
  try {
    await buildBundle(directory);
    // production base는 그대로 두고 시험 프로세스의 fetch 전송만 loopback으로 돌린다.
    await writeFile(preload, `const original = globalThis.fetch; globalThis.fetch = ((input, init) => { const url = String(input); if (!url.startsWith('https://openapi.tossinvest.com/')) throw new Error('unexpected-origin'); return original(url.replace('https://openapi.tossinvest.com', ${JSON.stringify(fake.url)}), init); });`);
    await client.connect(new StdioClientTransport({ command: process.execPath, args: ["--preload", preload, join(directory, "tossinvest-mcp.js")], env: { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "9223372036854775807" }, stderr: "pipe" }));
    const { result, body } = await tool(client, "get_sellable_quantity", { symbol: "AAPL" });
    expect(result.isError).toBeUndefined(); expect(result.structuredContent).toEqual(body);
    expect(body).toEqual({ result: { sellableQuantity: "9007199254740993.001" }, metadata: {}, sellable_quantity: "9007199254740993.001" });
    expect(fake.requests).toHaveLength(2);
    const request = fake.seen("GET")[0]!;
    expect(request.path).toBe("/api/v1/sellable-quantity"); expect(request.query.get("symbol")).toBe("AAPL");
    expect(request.body).toBe(""); expect(request.headers.get("x-tossinvest-account")).toBe("9223372036854775807");
  } finally { await client.close(); fake.stop(); await rm(directory, { recursive: true, force: true }); }
});
