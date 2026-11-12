import { OPERATIONS } from "../src/api-contract.ts";
import { FakeToss, credentials, stockInfo } from "./support.ts";

/** 조건주문 테스트가 함께 쓰는 합성 값이다. 실제 계정·주문과 관계가 없다. */
export const ACCOUNT = "9223372036854775807";
export const environment = () => ({
  ...credentials,
  TOSSINVEST_ACCOUNT_SEQ: ACCOUNT,
});
/** KST 오늘에서 days 일 뒤의 날짜다. 실행 날짜 검사를 지나는 값을 만든다. */
export const kstDate = (days = 30) =>
  new Date(Date.now() + 9 * 3600_000 + days * 86400_000)
    .toISOString()
    .slice(0, 10);
export type ConditionalTool =
  | "create_conditional_order"
  | "modify_conditional_order"
  | "cancel_conditional_order";

/** 공식 상세 schema 의 합성 원주문이다. PROFIT_RATE→STOP 정정과 OTO first 체결 뒤 취소 vector 다. */
export const originals = {
  modify_conditional_order: {
    conditionalOrderId: "cond-1",
    type: "SINGLE",
    status: "WATCHING",
    symbol: "ABC",
    market: "US",
    quantity: "1",
    orderType: "MARKET",
    expireDate: "2026-12-01",
    first: {
      type: "PROFIT_RATE",
      status: "WATCHING",
      orderSide: "SELL",
      triggerPrice: null,
      targetProfitRate: "10.5",
      orderPrice: null,
      triggeredOrderId: null,
    },
    second: null,
    createdAt: "2026-06-12T09:00:00+09:00",
  },
  cancel_conditional_order: {
    conditionalOrderId: "cond-1",
    type: "OTO",
    status: "ORDERED",
    symbol: "ABC",
    market: "US",
    quantity: "0.5",
    orderType: "LIMIT",
    first: {
      type: "STOP",
      status: "ORDERED",
      orderSide: "BUY",
      triggerPrice: "100",
      targetProfitRate: null,
      orderPrice: "100",
      triggeredOrderId: "ord-1",
    },
    second: {
      type: "STOP",
      status: "WATCHING",
      orderSide: "SELL",
      triggerPrice: "120",
      targetProfitRate: null,
      orderPrice: "120",
      triggeredOrderId: null,
    },
    createdAt: "2026-06-12T09:00:00+09:00",
  },
} as const;

export const models = (expireDate = kstDate()) => ({
  create_conditional_order: {
    symbol: "abc",
    type: "OCO",
    quantity: "002",
    orderType: "LIMIT",
    expireDate,
    first: { orderSide: "SELL", triggerPrice: "0110.00", orderPrice: "110" },
    second: { orderSide: "SELL", triggerPrice: "90", orderPrice: "90.0" },
    confirmHighValueOrder: false,
  },
  modify_conditional_order: {
    type: "SINGLE",
    quantity: "1",
    orderType: "MARKET",
    expireDate,
    first: { orderSide: "SELL", triggerPrice: "95" },
    conditionalOrderId: "cond-1",
  },
  cancel_conditional_order: { conditionalOrderId: "cond-1" },
});

export const PATHS: Record<ConditionalTool, [string, string]> = {
  create_conditional_order: ["POST", "/api/v1/conditional-orders"],
  modify_conditional_order: [
    "POST",
    "/api/v1/conditional-orders/cond-1/modify",
  ],
  cancel_conditional_order: ["DELETE", "/api/v1/conditional-orders/cond-1"],
};

export function setup(name: ConditionalTool, accountNo = "00001234") {
  const fake = new FakeToss(new Set(OPERATIONS.map((item) => item.id)));
  fake.routes.set(
    "GET /api/v1/accounts",
    () =>
      new Response(
        `{"result":[{"accountSeq":${ACCOUNT},"accountNo":"${accountNo}","accountType":"BROKERAGE"}]}`,
        { headers: { "content-type": "application/json" } },
      ),
  );
  fake.on("GET", "/api/v1/stocks", { result: [stockInfo("ABC", "합성 종목")] });
  fake.on("GET", "/api/v1/prices", {
    result: [
      { symbol: "ABC", timestamp: null, lastPrice: "100", currency: "USD" },
    ],
  });
  if (name !== "create_conditional_order")
    fake.on("GET", "/api/v1/conditional-orders/cond-1", {
      result: originals[name],
    });
  return { fake, env: environment() };
}
