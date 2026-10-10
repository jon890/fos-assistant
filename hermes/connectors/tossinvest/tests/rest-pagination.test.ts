import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { Tossinvest } from "../src/client.ts";
import { exactInt64, operationFor, parseApiResponse } from "../src/api-contract.ts";
import { createTossinvestServer } from "../src/server.ts";
import { FakeToss, accountNo, credentials, emptyHoldings, stockInfo, sampleOrder, json, tool, withMcp, expectFailure } from "./support.ts";

const vectors = JSON.parse(readFileSync(`${import.meta.dir}/fixtures/int64-vectors.json`, "utf8"));
const accountWire = (value: string) => `{"result":[{"accountNo":"${accountNo}","accountSeq":${value},"accountType":"BROKERAGE"}]}`;
const accountOperation = operationFor("GET", "/api/v1/accounts");
const tokenOperation = operationFor("POST", "/oauth2/token");
const env = { ...credentials, TOSSINVEST_ACCOUNT_SEQ: "9223372036854775807" };

test("manifest와 env는 양의 int64 경계가 같고 모델의 URL/계좌/token 입력은 HTTP 전에 거절한다", async () => {
  const manifest = JSON.parse(readFileSync(`${import.meta.dir}/../connector.json`, "utf8"));
  const pattern = new RegExp(manifest.fields.find((field: any) => field.key === "account").pattern);
  for (const value of ["1", "99999999999", "9007199254740993", "9223372036854775806", "9223372036854775807"]) expect(pattern.test(value)).toBe(true);
  for (const value of ["0", "-1", "01", "9223372036854775808", "9999999999999999999"]) expect(pattern.test(value)).toBe(false);
  // 정규식의 접두어 대안을 BigInt 기준의 독립 값과 비교한다.
  let seed = 17n;
  for (let i = 0; i < 1000; i++) {
    seed = (seed * 6364136223846793005n + 1442695040888963407n) % 10000000000000000000n;
    expect(pattern.test(seed.toString())).toBe(seed > 0n && seed <= 9223372036854775807n);
  }
  const fake = new FakeToss();
  try {
    await withMcp(createTossinvestServer({ apiBase: fake.url, env }), async client => {
      for (const key of ["account", "account_seq", "url", "apiBase", "token", "clientOrderId"]) {
        const result = await client.callTool({ name: "get_sellable_quantity", arguments: { symbol: "AAPL", [key]: "synthetic-private-input" } });
        expect(result.isError).toBe(true); expect(JSON.stringify(result)).not.toContain("synthetic-private-input");
      }
    });
    expect(fake.requests).toHaveLength(0);
  } finally { fake.stop(); }
});

test.each(vectors.validAccounts as [string, string][])("원문 accountSeq %s는 정확한 문자열 %s다", (source, expected) => {
  expect(parseApiResponse(accountWire(source), accountOperation, 200).result[0].accountSeq).toBe(expected);
});
test.each(vectors.invalidAccounts as string[])("잘못된 원문 accountSeq %s는 실패한다", source => {
  expect(() => parseApiResponse(accountWire(source), accountOperation, 200)).toThrow();
});
test.each(vectors.signedBounds as [string, string][])("signed int64 %s의 정확한 값은 %s다", (source, expected) => {
  expect(exactInt64(source).toString()).toBe(expected);
});

test("동명 비schema 필드와 문자열의 가짜 숫자는 변환하지 않는다", () => {
  const raw = '{"result":[{"accountNo":"synthetic","accountSeq":9007199254740993,"accountType":"FUTURE","extra":{"accountSeq":123,"nested":[[false,null,"accountSeq:9223372036854775807"]]}}],"accountSeq":9}';
  const parsed = parseApiResponse(raw, accountOperation, 200);
  expect(parsed.result[0].accountSeq).toBe("9007199254740993");
  expect(parsed.result[0].extra).toEqual({ accountSeq: 123, nested: [[false, null, "accountSeq:9223372036854775807"]] });
  expect(parsed.accountSeq).toBe(9);
  const native = JSON.parse;
  try {
    JSON.parse = ((raw: string, reviver: any) => native(raw, function (key, value) { return reviver.call(this, key, value); })) as typeof JSON.parse;
    expect(() => parseApiResponse(accountWire("1"), accountOperation, 200)).toThrow();
  } finally { JSON.parse = native; }
});

test("expires_in은 안전 정수/큰 정수 문자열이며 정확한 TTL 범위를 검사한다", async () => {
  for (const [source, expected] of [["1.0", 1], ["1e3", 1000], ["9007199254740993", "9007199254740993"], ["9223372036854775807", "9223372036854775807"]] as const) expect(parseApiResponse(`{"access_token":"fake","token_type":"Bearer","expires_in":${source}}`, tokenOperation, 200).expires_in).toBe(expected);
  for (const source of ["0", "-1", "9223372036854775808", "1.5", "1e-1"]) expect(() => parseApiResponse(`{"access_token":"fake","token_type":"Bearer","expires_in":${source}}`, tokenOperation, 200)).toThrow();
  const fake = new FakeToss();
  fake.routes.set("POST /oauth2/token", new Response('{"access_token":"fake","token_type":"Bearer","expires_in":9007199254740993}'));
  try {
    await expect(new Tossinvest({ apiBase: fake.url, env }).token()).rejects.toMatchObject({ code: "TOSSINVEST_UNAVAILABLE" });
    expect(fake.seen("GET")).toHaveLength(0);
  } finally { fake.stop(); }
});

test("실제 SDK content/structuredContent/선택지/헤더에서 int64를 보존하고 계좌는 마스킹한다", async () => {
  const fake = new FakeToss();
  fake.routes.set("GET /api/v1/accounts", new Response(`{"result":[{"accountNo":"${accountNo}","accountSeq":9007199254740993,"accountType":"BROKERAGE"},{"accountNo":"${accountNo}","accountSeq":9223372036854775807,"accountType":"FUTURE"}]}`));
  fake.on("GET", "/api/v1/holdings", { result: emptyHoldings });
  try {
    await withMcp(createTossinvestServer({ apiBase: fake.url, env }), async client => {
      const { result, body } = await tool(client, "list_accounts");
      expect(result.structuredContent).toEqual(body);
      expect(body.accounts).toEqual([{ account_seq: "9007199254740993", account_type: "BROKERAGE", label: "종합매매 ****8901" }, { account_seq: "9223372036854775807", account_type: "FUTURE", label: "기타 ****8901" }]);
      expect(body.result).toEqual([{ accountNo: "****8901", accountSeq: "9007199254740993", accountType: "BROKERAGE" }, { accountNo: "****8901", accountSeq: "9223372036854775807", accountType: "FUTURE" }]);
      expect(JSON.stringify(result)).not.toContain(accountNo);
      await tool(client, "get_holdings", { symbol: "LONGSYMBOL1234567890" });
    });
    const request = fake.seen("GET", "/api/v1/holdings")[0]!;
    expect(request.headers.get("x-tossinvest-account")).toBe("9223372036854775807");
    expect(request.query.get("symbol")).toBe("LONGSYMBOL1234567890");
  } finally { fake.stop(); }
});

test("잘못된 계좌 행은 실제 도구에서 전체 실패하며 정상 행을 일부 반환하지 않는다", async () => {
  const fake = new FakeToss(); fake.routes.set("GET /api/v1/accounts", new Response(accountWire("9223372036854775808")));
  try { await withMcp(createTossinvestServer({ apiBase: fake.url, env }), client => expectFailure(client, "TOSSINVEST_UNAVAILABLE", "list_accounts")); }
  finally { fake.stop(); }
});

test("200개와 긴 symbol, 공식 result의 boolean/null/decimal을 보존한다", async () => {
  const fake = new FakeToss();
  const stock = { ...stockInfo("LONGSYMBOL1234567890", "가".repeat(150)), market: "FUTURE_MARKET", leverageFactor: "-2.000000000000000000000000001", koreanMarketDetail: { liquidationTrading: false, nxtSupported: true, krxTradingSuspended: false, nxtTradingSuspended: null } };
  const price = { symbol: stock.symbol, currency: "USD", lastPrice: "987654321098765432109876543210", timestamp: null };
  fake.on("GET", "/api/v1/prices", { result: [price] }); fake.on("GET", "/api/v1/stocks", { result: [stock] });
  try {
    await withMcp(createTossinvestServer({ apiBase: fake.url, env }), async client => {
      const symbols = [stock.symbol, ...Array.from({ length: 199 }, (_, i) => `S${i}`)].join(",");
      const { result, body } = await tool(client, "get_quotes", { symbols });
      expect(body.result).toEqual({ prices: [price], stocks: [stock] });
      expect(body.metadata).toEqual({ prices: {}, stocks: {} });
      expect(result.structuredContent).toEqual(body);
      expect((body.quotes as any)[0].name).toBe("가".repeat(100));
      expect((body.quotes as any)[0].last_price).toBe(price.lastPrice);
      expect(fake.seen("GET")).toHaveLength(2);
      await expectFailure(client, "TOSSINVEST_INVALID_INPUT", "get_quotes", { symbols: `${symbols},ONE_MORE` });
      await expectFailure(client, "TOSSINVEST_INVALID_INPUT", "get_holdings", { symbol: "A,B" });
      expect(fake.seen("GET")).toHaveLength(2);
    });
  } finally { fake.stop(); }
});

test("CLOSED 두 페이지와 긴 cursor/id, OPEN 121행, timeInForce와 긴 기간을 보존한다", async () => {
  const fake = new FakeToss(); const cursor = `opaque+/% ${"x".repeat(200)}`; const order = { ...sampleOrder("id".repeat(90)), timeInForce: "OPG" };
  fake.sequence("GET", "/api/v1/orders", [() => json({ result: { orders: [order], nextCursor: cursor, hasNext: true } }), () => json({ result: { orders: [], nextCursor: null, hasNext: false } }), () => json({ result: { orders: Array.from({ length: 121 }, () => order), nextCursor: null, hasNext: false } })]);
  try {
    await withMcp(createTossinvestServer({ apiBase: fake.url, env }), async client => {
      const first = await tool(client, "list_orders", { status: "CLOSED", from: "2020-01-01", to: "2026-10-11" });
      expect(first.body.nextCursor).toBe(cursor); expect(first.body.hasNext).toBe(true);
      expect((first.body.orders as any)[0]).toMatchObject({ order_id: order.orderId, time_in_force: "OPG", quantity: order.quantity, price: order.price });
      expect(first.body.result).toEqual({ orders: [order], nextCursor: cursor, hasNext: true });
      const next = await tool(client, "list_orders", { status: "CLOSED", cursor, limit: 100 });
      expect(next.body.nextCursor).toBeNull(); expect(next.body.hasNext).toBe(false);
      const open = await tool(client, "list_orders", { status: "OPEN", cursor, limit: 100 });
      expect(open.body.orders).toHaveLength(121); expect((open.body.result as any).orders).toHaveLength(121);
      expect(open.body.has_more).toBe(false);
      await expectFailure(client, "TOSSINVEST_INVALID_INPUT", "list_orders", { status: "CLOSED", limit: 101 });
    });
    const requests = fake.seen("GET"); expect(requests).toHaveLength(3);
    expect(requests[0]!.query.get("limit")).toBe("20"); expect(requests[1]!.query.get("limit")).toBe("100"); expect(requests[1]!.query.get("cursor")).toBe(cursor);
    expect(Object.fromEntries(requests[2]!.query)).toEqual({ status: "OPEN" });
  } finally { fake.stop(); }
});

test("buying power의 symbol 유무와 단독 sellable의 정확한 복합 envelope/수신 건수를 검증한다", async () => {
  const fake = new FakeToss(); const buyingPower = { currency: "USD", cashBuyingPower: "-0.987654321098765432109876543" }; const sellableQuantity = { sellableQuantity: "987654321098765432109876543210" };
  fake.on("GET", "/api/v1/buying-power", { result: buyingPower }); fake.on("GET", "/api/v1/sellable-quantity", { result: sellableQuantity });
  try {
    await withMcp(createTossinvestServer({ apiBase: fake.url, env }), async client => {
      const without = await tool(client, "get_buying_power", { currency: "USD" });
      expect(without.body).toEqual({ result: { buyingPower, sellableQuantity: null }, metadata: { buyingPower: {}, sellableQuantity: null }, currency: "USD", cash_buying_power: buyingPower.cashBuyingPower, sellable_quantity: null });
      expect(fake.seen("GET")).toHaveLength(1);
      const withSymbol = await tool(client, "get_buying_power", { currency: "USD", symbol: "AAPL" });
      expect(withSymbol.body).toEqual({ result: { buyingPower, sellableQuantity }, metadata: { buyingPower: {}, sellableQuantity: {} }, currency: "USD", cash_buying_power: buyingPower.cashBuyingPower, sellable_quantity: sellableQuantity.sellableQuantity });
      expect(fake.seen("GET")).toHaveLength(3);
      const single = await tool(client, "get_sellable_quantity", { symbol: "AAPL" });
      expect(single.body).toEqual({ result: sellableQuantity, metadata: {}, sellable_quantity: sellableQuantity.sellableQuantity }); expect(single.result.structuredContent).toEqual(single.body);
      expect(fake.seen("GET")).toHaveLength(4);
    });
    for (const request of fake.seen("GET")) { expect(request.body).toBe(""); expect(request.headers.get("x-tossinvest-account")).toBe(env.TOSSINVEST_ACCOUNT_SEQ); }
  } finally { fake.stop(); }
});
