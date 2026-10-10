import { describe, expect, test } from "bun:test";
import { createTossinvestServer } from "../src/server.ts";
import {
  FakeToss,
  emptyHoldings,
  credentials,
  expectFailure,
  json,
  tool,
  withMcp,
} from "./support.ts";

/** 모두 지어낸 값이다. 실제 계좌, 종목 보유, 주문과 관계가 없다. */
const accountSeq = "7";
const env = { ...credentials, TOSSINVEST_ACCOUNT_SEQ: accountSeq };

function setup(environment: Record<string, string | undefined> = env) {
  const fake = new FakeToss();
  const server = createTossinvestServer({ apiBase: fake.url, env: environment });
  return { fake, server };
}

async function run(
  fake: FakeToss,
  server: ReturnType<typeof createTossinvestServer>,
  work: Parameters<typeof withMcp>[1],
) {
  try {
    await withMcp(server, work);
  } finally {
    fake.stop();
  }
}

// 공제 전 값과 공제 후 값을 모두 다르게 두어 어느 칸이 어디서 왔는지 드러나게 한다.
const holdings = {
  result: {
    totalPurchaseAmount: { krw: "1000000", usd: null },
    marketValue: {
      amount: { krw: "1200000", usd: null },
      amountAfterCost: { krw: "1190000", usd: null },
    },
    profitLoss: {
      amount: { krw: "200000", usd: null },
      amountAfterCost: { krw: "190000", usd: null },
      rate: "20.00",
      rateAfterCost: "19.00",
    },
    dailyProfitLoss: { amount: { krw: "5000", usd: "3.21" }, rate: "0.42" },
    items: [
      {
        symbol: "AAPL",
        name: "Fake Apple",
        marketCountry: "US",
        currency: "USD",
        quantity: "1.234567",
        lastPrice: "190.50",
        averagePurchasePrice: "150.25",
        marketValue: { purchaseAmount: "185.49", amount: "235.18", amountAfterCost: "234.90" },
        profitLoss: { amount: "49.69", amountAfterCost: "49.41", rate: "26.79", rateAfterCost: "26.64" },
        dailyProfitLoss: { amount: "1.11", rate: "0.47" },
        cost: { commission: "0.12", tax: null },
      },
    ],
  },
};

describe("get_holdings", () => {
  test("계좌 헤더에 순번을 싣고 공제 전 값과 공제 후 값을 각 칸으로 옮긴다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/holdings", holdings);
    await run(fake, server, async (client) => {
      expect((await tool(client, "get_holdings")).body).toEqual({
        result: holdings.result, metadata: {},
        total: {
          purchase_amount: { krw: "1000000", usd: null },
          market_value: { krw: "1200000", usd: null },
          market_value_after_cost: { krw: "1190000", usd: null },
          profit_loss: { krw: "200000", usd: null },
          profit_loss_after_cost: { krw: "190000", usd: null },
          profit_loss_rate: "20.00",
          profit_loss_rate_after_cost: "19.00",
          daily_profit_loss: { krw: "5000", usd: "3.21" },
          daily_profit_loss_rate: "0.42",
        },
        items: [
          {
            symbol: "AAPL",
            name: "Fake Apple",
            market: "US",
            currency: "USD",
            quantity: "1.234567",
            last_price: "190.50",
            average_purchase_price: "150.25",
            purchase_amount: "185.49",
            market_value: "235.18",
            market_value_after_cost: "234.90",
            profit_loss: "49.69",
            profit_loss_after_cost: "49.41",
            profit_loss_rate: "26.79",
            profit_loss_rate_after_cost: "26.64",
            daily_profit_loss: "1.11",
            daily_profit_loss_rate: "0.47",
            commission: "0.12",
            tax: null,
          },
        ],
      });
    });
    const request = fake.seen("GET", "/api/v1/holdings")[0]!;
    expect(request.headers.get("x-tossinvest-account")).toBe(accountSeq);
  });

  test("필수 필드와 값의 schema 위반은 전체 응답 오류다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/holdings", {
      result: {
        profitLoss: { rate: ["20.00"] },
        items: [
          {
            symbol: "005930",
            quantity: { raw: "1" },
            lastPrice: [70000],
            currency: "x".repeat(80),
          },
        ],
      },
    });
    await run(fake, server, client => expectFailure(client, "TOSSINVEST_UNAVAILABLE", "get_holdings"));
  });

  test("decimal의 공식 길이와 문자열 타입 위반은 전체 응답 오류다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/holdings", {
      result: {
        items: [
          {
            symbol: "005930",
            quantity: "9".repeat(65),
            lastPrice: "9".repeat(64),
            averagePurchasePrice: "1,000",
            cost: { commission: "-0.5", tax: 3 },
          },
        ],
      },
    });
    await run(fake, server, client => expectFailure(client, "TOSSINVEST_UNAVAILABLE", "get_holdings"));
  });

  test("100자를 넘는 종목 이름은 자른다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/holdings", {
      result: { ...holdings.result, items: [{ ...holdings.result.items[0], symbol: "005930", name: "가".repeat(150) }] },
    });
    await run(fake, server, async (client) => {
      const { body } = await tool(client, "get_holdings");
      const items = body.items as Array<{ name: string }>;
      expect(items[0]!.name).toBe("가".repeat(100));
    });
  });
});

describe("계좌 순번", () => {
  test.each([
    ["없음", undefined],
    ["빈 값", ""],
    ["숫자가 아님", "seq-7"],
    ["int64 초과", "9223372036854775808"],
    ["0", "0"],
    ["음수", "-1"],
    ["끝 줄바꿈", "1\n"],
  ])("env 가 %s 이면 요청 없이 TOSSINVEST_ACCOUNT_NOT_FOUND 다", async (_label, seq) => {
    const { fake, server } = setup({ ...credentials, TOSSINVEST_ACCOUNT_SEQ: seq });
    await run(fake, server, (client) =>
      expectFailure(client, "TOSSINVEST_ACCOUNT_NOT_FOUND", "get_holdings"),
    );
    expect(fake.requests).toHaveLength(0);
  });

  test("10자리 순번은 받는다", async () => {
    const { fake, server } = setup({ ...credentials, TOSSINVEST_ACCOUNT_SEQ: "1234567890" });
    fake.on("GET", "/api/v1/holdings", { result: emptyHoldings });
    await run(fake, server, async (client) => {
      expect((await tool(client, "get_holdings")).result.isError).toBeUndefined();
    });
    const request = fake.seen("GET", "/api/v1/holdings")[0]!;
    expect(request.headers.get("x-tossinvest-account")).toBe("1234567890");
  });

  test("/holdings 의 404 account-not-found 는 TOSSINVEST_ACCOUNT_NOT_FOUND 다", async () => {
    const { fake, server } = setup();
    fake.fail("GET", "/api/v1/holdings", 404, "account-not-found");
    await run(fake, server, (client) =>
      expectFailure(client, "TOSSINVEST_ACCOUNT_NOT_FOUND", "get_holdings"),
    );
  });

  test("/sellable-quantity 의 400 account-not-found 는 TOSSINVEST_ACCOUNT_NOT_FOUND 다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/buying-power", { result: { currency: "KRW", cashBuyingPower: "1000" } });
    fake.fail("GET", "/api/v1/sellable-quantity", 400, "account-not-found");
    await run(fake, server, (client) =>
      expectFailure(client, "TOSSINVEST_ACCOUNT_NOT_FOUND", "get_buying_power", {
        currency: "KRW",
        symbol: "005930",
      }),
    );
  });
});

describe("get_buying_power", () => {
  test("symbol 이 없으면 /buying-power 만 부르고 sellable_quantity 는 null 이다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/buying-power", { result: { currency: "USD", cashBuyingPower: "1234.56" } });
    await run(fake, server, async (client) => {
      expect((await tool(client, "get_buying_power", { currency: "USD" })).body).toEqual({
        result: { buyingPower: { currency: "USD", cashBuyingPower: "1234.56" }, sellableQuantity: null },
        metadata: { buyingPower: {}, sellableQuantity: null },
        currency: "USD",
        cash_buying_power: "1234.56",
        sellable_quantity: null,
      });
    });
    const power = fake.seen("GET", "/api/v1/buying-power");
    expect(power).toHaveLength(1);
    expect(power[0]!.query.get("currency")).toBe("USD");
    expect(power[0]!.headers.get("x-tossinvest-account")).toBe(accountSeq);
    expect(fake.seen("GET", "/api/v1/sellable-quantity")).toHaveLength(0);
  });

  test("symbol 을 주면 /sellable-quantity 를 한 번 불러 매도 가능 수량을 싣는다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/buying-power", { result: { currency: "KRW", cashBuyingPower: "500000" } });
    fake.on("GET", "/api/v1/sellable-quantity", { result: { sellableQuantity: "12" } });
    await run(fake, server, async (client) => {
      const { body } = await tool(client, "get_buying_power", { currency: "KRW", symbol: "005930" });
      expect(body).toEqual({ currency: "KRW", cash_buying_power: "500000", sellable_quantity: "12", result: { buyingPower: { currency: "KRW", cashBuyingPower: "500000" }, sellableQuantity: { sellableQuantity: "12" } }, metadata: { buyingPower: {}, sellableQuantity: {} } });
    });
    const sellable = fake.seen("GET", "/api/v1/sellable-quantity");
    expect(sellable).toHaveLength(1);
    expect(sellable[0]!.query.get("symbol")).toBe("005930");
    expect(sellable[0]!.headers.get("x-tossinvest-account")).toBe(accountSeq);
    expect(fake.seen("GET", "/api/v1/buying-power")).toHaveLength(1);
  });

  test("표시 currency는 자르고 공식 result는 보존하며 잘못된 타입은 오류다", async () => {
    const { fake, server } = setup();
    fake.sequence("GET", "/api/v1/buying-power", [
      () => json({ result: { currency: "x".repeat(80), cashBuyingPower: "1" } }),
      () => json({ result: { currency: { code: "USD" }, cashBuyingPower: "2" } }),
    ]);
    await run(fake, server, async (client) => {
      const first = await tool(client, "get_buying_power", { currency: "KRW" });
      expect(first.body.currency).toBe("x".repeat(64));
      expect((first.body.result as any).buyingPower.currency).toBe("x".repeat(80));
      await expectFailure(client, "TOSSINVEST_UNAVAILABLE", "get_buying_power", { currency: "USD" });
    });
  });

  test.each([
    [{ currency: "EUR" }, "EUR"],
    [{}, "currency 없음"],
    [{ currency: "KRW", symbol: "005930;AAPL" }, "허용하지 않는 종목 기호"],
  ])("%j(%s)는 요청 없이 TOSSINVEST_INVALID_INPUT 이다", async (input) => {
    const { fake, server } = setup();
    await run(fake, server, (client) =>
      expectFailure(client, "TOSSINVEST_INVALID_INPUT", "get_buying_power", input),
    );
    expect(fake.requests).toHaveLength(0);
  });
});

const order = (index: number) => ({
  orderId: `fake-order-${index}`,
  symbol: "AAPL",
  side: "BUY",
  orderType: "LIMIT",
  timeInForce: "DAY",
  status: "FILLED",
  price: "190.50",
  quantity: "0.5",
  orderAmount: "95.25",
  currency: "USD",
  orderedAt: "2026-03-02T10:00:00+09:00",
  canceledAt: null,
  execution: {
    filledQuantity: "0.5",
    averageFilledPrice: "190.40",
    filledAmount: "95.20",
    commission: "0.05",
    tax: null,
    filledAt: "2026-03-02T23:31:00+09:00",
    settlementDate: "2026-03-04",
  },
});

describe("list_orders", () => {
  test("CLOSED 는 기본 limit=20 으로 한 쪽만 읽고 hasNext 를 has_more 로 옮긴다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/orders", {
      result: { orders: [order(1)], nextCursor: "fake-cursor", hasNext: true },
    });
    await run(fake, server, async (client) => {
      const { body } = await tool(client, "list_orders", {
        status: "CLOSED",
        from: "2026-03-01",
        to: "2026-03-31",
        symbol: "AAPL",
      });
      expect(body).toEqual({
        result: { orders: [order(1)], nextCursor: "fake-cursor", hasNext: true }, metadata: {}, nextCursor: "fake-cursor", hasNext: true,
        orders: [
          {
            order_id: "fake-order-1",
            symbol: "AAPL",
            side: "BUY",
            order_type: "LIMIT",
            time_in_force: "DAY",
            status: "FILLED",
            price: "190.50",
            quantity: "0.5",
            order_amount: "95.25",
            currency: "USD",
            filled_quantity: "0.5",
            average_filled_price: "190.40",
            filled_amount: "95.20",
            commission: "0.05",
            tax: null,
            ordered_at: "2026-03-02T10:00:00+09:00",
            filled_at: "2026-03-02T23:31:00+09:00",
            canceled_at: null,
            settlement_date: "2026-03-04",
          },
        ],
        has_more: true,
      });
    });
    const request = fake.seen("GET", "/api/v1/orders")[0]!;
    expect(Object.fromEntries(request.query)).toEqual({
      status: "CLOSED",
      symbol: "AAPL",
      from: "2026-03-01",
      to: "2026-03-31",
      limit: "20",
    });
    expect(request.headers.get("x-tossinvest-account")).toBe(accountSeq);
  });

  test("CLOSED 의 hasNext 가 거짓이면 has_more 도 거짓이다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/orders", { result: { orders: [], nextCursor: null, hasNext: false } });
    await run(fake, server, async (client) => {
      expect((await tool(client, "list_orders", { status: "CLOSED" })).body).toEqual({
        result: { orders: [], nextCursor: null, hasNext: false }, metadata: {}, nextCursor: null, hasNext: false,
        orders: [],
        has_more: false,
      });
    });
  });

  test.each([
    [100, 100, false],
    [101, 101, false],
  ])("OPEN 은 limit 과 cursor 없이 읽고, %i건을 받으면 %i건과 has_more %p 다", async (given, kept, more) => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/orders", {
      result: {
        orders: Array.from({ length: given }, (_, index) => ({ ...order(index), status: "PENDING" })),
        nextCursor: null,
        hasNext: false,
      },
    });
    await run(fake, server, async (client) => {
      const { body } = await tool(client, "list_orders", { status: "OPEN" });
      const orders = body.orders as Array<{ order_id: string }>;
      expect(orders).toHaveLength(kept);
      expect(orders.at(-1)!.order_id).toBe(`fake-order-${kept - 1}`);
      expect(body.has_more).toBe(more);
    });
    const request = fake.seen("GET", "/api/v1/orders")[0]!;
    expect(Object.fromEntries(request.query)).toEqual({ status: "OPEN" });
  });

  test("from 과 to 를 포함해 366일인 기간은 받는다", async () => {
    const { fake, server } = setup();
    fake.on("GET", "/api/v1/orders", { result: { orders: [], nextCursor: null, hasNext: false } });
    await run(fake, server, async (client) => {
      const { result } = await tool(client, "list_orders", {
        status: "CLOSED",
        from: "2024-01-01",
        to: "2024-12-31",
      });
      expect(result.isError).toBeUndefined();
    });
    expect(fake.seen("GET", "/api/v1/orders")).toHaveLength(1);
  });

  test.each([
    [{ status: "CLOSED", from: "2026-03-02", to: "2026-03-01" }, "from 이 to 보다 늦다"],
    [{ status: "CLOSED", from: "2026-02-30" }, "없는 날짜"],
    [{ status: "CLOSED", to: "2026/03/01" }, "형식이 틀린 날짜"],
    [{ status: "DONE" }, "모르는 status"],
    [{}, "status 없음"],
    [{ status: "OPEN", symbol: "A,B" }, "comma 단일 종목"],
  ])("%j(%s)는 요청 없이 TOSSINVEST_INVALID_INPUT 이다", async (input) => {
    const { fake, server } = setup();
    await run(fake, server, (client) =>
      expectFailure(client, "TOSSINVEST_INVALID_INPUT", "list_orders", input),
    );
    expect(fake.requests).toHaveLength(0);
  });
});
