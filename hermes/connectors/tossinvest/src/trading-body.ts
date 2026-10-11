import { z } from "zod";
import { TossinvestError } from "./errors.ts";
import type { Exchange } from "./transport.ts";
import { SYMBOL } from "./constants.ts";
import { operationFor } from "./api-contract.ts";

export const reject = (): never => {
  throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
};
export const UNKNOWN = "TOSSINVEST_OUTCOME_UNKNOWN";
/** fetch가 시작된 뒤에는 명백한 거절만 FAILED로 보존한다. */
export function financialFailure(error: unknown, started: boolean): never {
  if (
    started &&
    !(
      error instanceof TossinvestError &&
      error.code === "TOSSINVEST_INVALID_INPUT"
    )
  )
    throw new TossinvestError(UNKNOWN);
  throw error;
}
export const TRADE_OPERATIONS = {
  create_order: "CREATE",
  modify_order: "MODIFY",
  cancel_order: "CANCEL",
} as const;
export type TradeTool = keyof typeof TRADE_OPERATIONS;
export type Args = Record<string, any>;
export function financialOperation(method: "POST" | "DELETE", path: string) {
  const operation = operationFor(method, path);
  if (
    ![
      "createOrder",
      "modifyOrder",
      "cancelOrder",
      "cancelConditionalOrder",
    ].includes(operation.id)
  )
    reject();
  return operation;
}
const text = z.string().min(1);
const decimal = text.max(30).regex(/^[0-9]+(?:\.[0-9]+)?$/);
const orderId = text.max(256).regex(/^[^\x00-\x1f\x7f-\x9f]+$/);
const orderType = z.enum(["LIMIT", "MARKET"]);
const side = z.enum(["BUY", "SELL"]);
const tif = z.enum(["DAY", "OPG", "CLS"]);
const quantities = {
  quantity: decimal.optional(),
  price: decimal.optional(),
};
export const MODEL_SCHEMAS = {
  create_order: z.strictObject({
    symbol: text.regex(SYMBOL),
    side,
    orderType,
    ...quantities,
    orderAmount: decimal.optional(),
    timeInForce: tif.optional(),
    confirmHighValueOrder: z.literal(false).optional(),
  }),
  modify_order: z.strictObject({
    orderId,
    orderType,
    ...quantities,
    confirmHighValueOrder: z.literal(false).optional(),
  }),
  cancel_order: z.strictObject({ orderId }),
};
export const expectedSchema = z.strictObject({
  orderId,
  symbol: text.regex(SYMBOL),
  currency: z.enum(["KRW", "USD"]),
  side,
  quantity: decimal,
  orderAmount: decimal.nullable(),
  price: decimal.nullable(),
  orderType,
  timeInForce: tif,
  status: z.enum(["PENDING", "PARTIAL_FILLED"]),
  execution: z.strictObject({ filledQuantity: decimal }),
});
const scope = { account_seq: text, market: text, currency: text };
export const EXECUTION_SCHEMAS = {
  create_order: z.strictObject({
    symbol: text.regex(SYMBOL),
    side,
    orderType,
    ...quantities,
    orderAmount: decimal.optional(),
    timeInForce: tif,
    clientOrderId: z.uuid(),
    ...scope,
  }),
  modify_order: z.strictObject({
    orderId,
    orderType,
    ...quantities,
    ...scope,
    expected_order: expectedSchema,
  }),
  cancel_order: z.strictObject({
    orderId,
    ...scope,
    expected_order: expectedSchema,
  }),
};

/** 숫자 변환 없이 decimal 표기만 정규화한다. */
export function canonicalDecimal(value: unknown, zero = false): string {
  if (typeof value !== "string" || !decimal.safeParse(value).success) reject();
  const [whole, fraction = ""] = (value as string).split(".");
  const integer = whole!.replace(/^0+(?=\d)/, "");
  const tail = fraction.replace(/0+$/, "");
  const normalized = integer + (tail ? `.${tail}` : "");
  if (!zero && normalized === "0") reject();
  return normalized;
}

/** 공식 StockInfo.market과 currency가 모두 맞아야 시장을 정한다. */
export function country(market: unknown, currency: unknown): "KR" | "US" {
  if (
    ["KOSPI", "KOSDAQ", "KR_ETC"].includes(String(market)) &&
    currency === "KRW"
  )
    return "KR";
  if (
    ["NYSE", "NASDAQ", "AMEX", "US_ETC"].includes(String(market)) &&
    currency === "USD"
  )
    return "US";
  return reject();
}
function priceCheck(price: unknown, region: string) {
  const value = canonicalDecimal(price);
  const [integer, fraction = ""] = value.split(".");
  const scale = region === "KR" ? 0 : integer === "0" ? 4 : 2;
  if (fraction.length > scale) reject();
}

/** 승인 후에는 기본값이나 값을 보충하지 않고 공식 본문의 칸만 고른다. */
export function tradingBody(tool: TradeTool, args: Args): Args {
  if (!EXECUTION_SCHEMAS[tool].safeParse(args).success) reject();
  const region = country(args.market, args.currency);
  if (
    tool !== "create_order" &&
    (args.orderId !== args.expected_order.orderId ||
      args.currency !== args.expected_order.currency)
  )
    reject();
  if (tool === "cancel_order") return {};
  if ((args.orderType === "LIMIT") !== Object.hasOwn(args, "price")) reject();
  if (args.price !== undefined) priceCheck(args.price, region);
  if (tool === "modify_order") {
    // 금액 원주문의 LIMIT 전환은 현재 표시 값·소비 계약으로 표현할 수 없다.
    if (args.expected_order.orderAmount !== null && args.orderType === "LIMIT")
      reject();
    if (
      !(
        region === "KR"
          ? ["DAY", "OPG"]
          : args.orderType === "LIMIT"
            ? ["DAY", "CLS"]
            : ["DAY"]
      ).includes(args.expected_order.timeInForce)
    )
      reject();
    if ((region === "KR") !== Object.hasOwn(args, "quantity")) reject();
    if (
      args.quantity !== undefined &&
      canonicalDecimal(args.quantity).includes(".")
    )
      reject();
    return { orderType: args.orderType, ...pick(args, ["quantity", "price"]) };
  }
  if (Object.hasOwn(args, "quantity") === Object.hasOwn(args, "orderAmount"))
    reject();
  if (
    !(
      region === "KR"
        ? ["DAY", "OPG"]
        : args.orderType === "LIMIT"
          ? ["DAY", "CLS"]
          : ["DAY"]
    ).includes(args.timeInForce)
  )
    reject();
  if (args.orderAmount !== undefined) {
    canonicalDecimal(args.orderAmount);
    if (
      region !== "US" ||
      args.orderType !== "MARKET" ||
      args.timeInForce !== "DAY"
    )
      reject();
    return pick(args, [
      "symbol",
      "side",
      "orderType",
      "orderAmount",
      "clientOrderId",
    ]);
  }
  const value = canonicalDecimal(args.quantity);
  if (
    value.includes(".") &&
    !(
      region === "US" &&
      args.orderType === "MARKET" &&
      args.side === "SELL" &&
      value.split(".")[1]!.length <= 6
    )
  )
    reject();
  return pick(args, [
    "symbol",
    "side",
    "orderType",
    "quantity",
    "price",
    "timeInForce",
    "clientOrderId",
  ]);
}
const pick = (args: Args, fields: string[]): Args =>
  Object.fromEntries(
    fields
      .filter((key) => Object.hasOwn(args, key))
      .map((key) => [key, args[key]]),
  );

/** 재조회에서는 거래 의도와 체결 수량을 보존하고 표시용 부가 필드는 제외한다. */
export function expectedOrder(order: Args): Args {
  const projected: Args = {
    ...pick(order, [
      "orderId",
      "symbol",
      "currency",
      "side",
      "quantity",
      "orderType",
      "timeInForce",
      "status",
    ]),
    orderAmount: order.orderAmount ?? null,
    price: order.price ?? null,
    execution: { filledQuantity: order.execution?.filledQuantity },
  };
  if (!expectedSchema.safeParse(projected).success) reject();
  canonicalDecimal(projected.execution.filledQuantity, true);
  canonicalDecimal(projected.quantity);
  if ((projected.orderType === "LIMIT") !== (projected.price !== null))
    reject();
  if (projected.price !== null) canonicalDecimal(projected.price);
  if (projected.orderAmount !== null) {
    canonicalDecimal(projected.orderAmount);
    if (projected.orderType !== "MARKET") reject();
  }
  return projected;
}

// 1.2.24의 operation별 명백한 거절 코드다. 알 수 없는 코드와 처리 중 응답은 UNKNOWN이다.
const TERMINAL = new Set([
  "already-filled",
  "already-canceled",
  "already-modified",
  "already-rejected",
]);
const UNAUTHORIZED = [
  "invalid-token",
  "expired-token",
  "token-revoked",
  "login-user-not-found",
];
const REJECTIONS: Record<string, Record<number, string[]>> = {
  createOrder: {
    400: [
      "account-header-required",
      "confirm-high-value-required",
      "invalid-request",
    ],
    401: UNAUTHORIZED,
    422: [
      "account-restricted",
      "amount-order-outside-regular-hours",
      "fractional-quantity-outside-regular-hours",
      "idempotency-key-conflict",
      "insufficient-buying-power",
      "investor-exchange-not-integrated",
      "market-not-supported-for-stock",
      "max-order-amount-exceeded",
      "order-hours-closed",
      "order-type-not-allowed",
      "prerequisite-required",
      "price-out-of-range",
      "stock-restricted",
    ],
  },
  modifyOrder: {
    400: [
      "account-header-required",
      "confirm-high-value-required",
      "invalid-request",
      "us-modify-quantity-not-supported",
    ],
    401: UNAUTHORIZED,
    404: ["account-not-found", "order-not-found"],
    422: [
      "account-restricted",
      "investor-exchange-not-integrated",
      "max-order-amount-exceeded",
      "modify-restricted",
      "order-hours-closed",
      "prerequisite-required",
    ],
  },
  cancelOrder: {
    400: ["account-header-required"],
    401: UNAUTHORIZED,
    404: ["order-not-found"],
    422: ["cancel-restricted", "order-hours-closed"],
  },
};
export function financialResult(operationId: string, response: Exchange): any {
  const data = response.data;
  if (!response.ok) {
    const error = data?.error;
    const schemaMatches =
      typeof error?.requestId === "string" &&
      typeof error?.message === "string" &&
      typeof error?.code === "string" &&
      (error.data === undefined ||
        error.data === null ||
        (typeof error.data === "object" && !Array.isArray(error.data)));
    const rejected =
      schemaMatches &&
      (REJECTIONS[operationId]?.[response.status]?.includes(error.code) ||
        (response.status === 409 &&
          ["modifyOrder", "cancelOrder"].includes(operationId) &&
          TERMINAL.has(error.code)));
    throw new TossinvestError(rejected ? "TOSSINVEST_INVALID_INPUT" : UNKNOWN);
  }
  if (
    response.status === 204 &&
    operationId === "cancelConditionalOrder" &&
    response.empty
  )
    return {};
  if (
    response.status !== 200 ||
    typeof data?.result?.orderId !== "string" ||
    !data.result.orderId ||
    data.result.orderId.length > 256
  )
    throw new TossinvestError(UNKNOWN);
  return data;
}
