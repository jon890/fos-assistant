import { expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { OPERATIONS, CURRENT_OPERATIONS, CONTRACT_SHA256, matchesOperation } from "../src/api-contract.ts";
import { createTossinvestServer } from "../src/server.ts";
import { FakeToss, credentials, tool, withMcp } from "./support.ts";

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
  for (const [path, result] of Object.entries({ accounts: [], prices: [], stocks: [], holdings: { items: [] }, "buying-power": { currency: "KRW", cashBuyingPower: "1" }, "sellable-quantity": { sellableQuantity: "2" }, orders: { orders: [], hasNext: false } })) fake.on("GET", `/api/v1/${path}`, { result });
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
