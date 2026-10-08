import { ORDER_PERIOD_MAX_DAYS, SYMBOL } from "./constants.ts";
import { TossinvestError } from "./errors.ts";
import { decimalValue as decimal, serviceValue as value } from "./values.ts";

const ORDER_STATUSES = new Set(["OPEN", "CLOSED"]);
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const DAY_MS = 24 * 60 * 60 * 1000;

export const isObject = (value: unknown): value is Record<string, any> =>
  Boolean(value) && typeof value === "object" && !Array.isArray(value);

/** 빈 글은 주지 않은 것으로 본다. 모델이 선택 인자에 빈 글을 넣는 일이 있다. */
export const optional = (raw: string | undefined) => {
  const trimmed = raw?.trim() ?? "";
  return trimmed === "" ? null : trimmed;
};

export function parseSymbol(raw: string | undefined): string | null {
  const symbol = optional(raw);
  if (symbol !== null && !SYMBOL.test(symbol))
    throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
  return symbol;
}

/** `YYYY-MM-DD` 꼴의 실제 날짜를 UTC 자정의 ms 로 바꾼다. 없는 날짜(2월 30일 등)는 거절한다. */
function parseDate(raw: string): number {
  if (!DATE.test(raw)) throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
  const [year, month, day] = raw.split("-").map(Number) as [number, number, number];
  const time = Date.UTC(year, month - 1, day);
  const date = new Date(time);
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  )
    throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
  return time;
}

export interface OrderInput {
  status?: string;
  from?: string;
  to?: string;
  symbol?: string;
  output?: string;
}

/**
 * 주문 내역 질의를 검사해 만든다. 쪽을 넘기는 `cursor` 와 `limit` 은 싣지 않는다.
 * 기간은 `from` 과 `to` 를 모두 포함해 센다. 틀리면 요청 없이 거절한다.
 */
export function orderQuery(input: OrderInput): URLSearchParams {
  const status = input.status?.trim() ?? "";
  if (!ORDER_STATUSES.has(status))
    throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
  const symbol = parseSymbol(input.symbol);
  const from = optional(input.from);
  const to = optional(input.to);
  const fromTime = from === null ? null : parseDate(from);
  const toTime = to === null ? null : parseDate(to);
  if (fromTime !== null && toTime !== null) {
    const days = (toTime - fromTime) / DAY_MS + 1;
    if (days < 1 || days > ORDER_PERIOD_MAX_DAYS)
      throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
  }
  const query = new URLSearchParams({ status });
  if (symbol !== null) query.set("symbol", symbol);
  if (from !== null) query.set("from", from);
  if (to !== null) query.set("to", to);
  return query;
}

/** API 의 주문 하나를 결과 항목으로 옮긴다. */
export function orderRow(row: Record<string, any>) {
  const execution = isObject(row.execution) ? row.execution : {};
  return {
    order_id: value(row.orderId),
    symbol: value(row.symbol),
    side: value(row.side),
    order_type: value(row.orderType),
    status: value(row.status),
    price: decimal(row.price),
    quantity: decimal(row.quantity),
    order_amount: decimal(row.orderAmount),
    currency: value(row.currency),
    filled_quantity: decimal(execution.filledQuantity),
    average_filled_price: decimal(execution.averageFilledPrice),
    filled_amount: decimal(execution.filledAmount),
    commission: decimal(execution.commission),
    tax: decimal(execution.tax),
    ordered_at: value(row.orderedAt),
    filled_at: value(execution.filledAt),
    canceled_at: value(row.canceledAt),
    settlement_date: value(execution.settlementDate),
  };
}
