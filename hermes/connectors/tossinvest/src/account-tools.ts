import { z } from "zod";
import {
  NAME_MAX_CHARS,
  ORDER_PERIOD_MAX_DAYS,
  ORDERS_MAX,
  SYMBOL,
} from "./constants.ts";
import { TossinvestError } from "./errors.ts";
import { decimalValue as decimal, serviceValue as value, truncateCodePoints } from "./values.ts";
import type { Tossinvest } from "./client.ts";
import type { RegisterTool } from "./tool-registration.ts";

const CURRENCIES = new Set(["KRW", "USD"]);
const ORDER_STATUSES = new Set(["OPEN", "CLOSED"]);
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const DAY_MS = 24 * 60 * 60 * 1000;

const isObject = (value: unknown): value is Record<string, any> =>
  Boolean(value) && typeof value === "object" && !Array.isArray(value);

/** 합계 금액 `{krw, usd}` 를 옮긴다. `usd` 는 API 가 null 로 줄 수 있다. */
const money = (raw: unknown) =>
  isObject(raw) ? { krw: decimal(raw.krw), usd: decimal(raw.usd) } : null;

const objects = (raw: unknown): Record<string, any>[] =>
  Array.isArray(raw) ? raw.filter(isObject) : [];

const result = (data: any): Record<string, any> =>
  isObject(data?.result) ? data.result : {};

/** 빈 글은 주지 않은 것으로 본다. 모델이 선택 인자에 빈 글을 넣는 일이 있다. */
const optional = (raw: string | undefined) => {
  const trimmed = raw?.trim() ?? "";
  return trimmed === "" ? null : trimmed;
};

function parseSymbol(raw: string | undefined): string | null {
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

/** 합계다. 금액은 `{krw, usd}` 이고 공제 전 값과 비용 공제 후 값을 함께 싣는다. */
function holdingsTotal(data: Record<string, any>) {
  const marketValue = isObject(data.marketValue) ? data.marketValue : {};
  const profitLoss = isObject(data.profitLoss) ? data.profitLoss : {};
  const daily = isObject(data.dailyProfitLoss) ? data.dailyProfitLoss : {};
  return {
    purchase_amount: money(data.totalPurchaseAmount),
    market_value: money(marketValue.amount),
    market_value_after_cost: money(marketValue.amountAfterCost),
    profit_loss: money(profitLoss.amount),
    profit_loss_after_cost: money(profitLoss.amountAfterCost),
    profit_loss_rate: decimal(profitLoss.rate),
    profit_loss_rate_after_cost: decimal(profitLoss.rateAfterCost),
    daily_profit_loss: money(daily.amount),
    daily_profit_loss_rate: decimal(daily.rate),
  };
}

/** 종목 하나다. 금액은 그 종목의 거래 통화 기준 글 하나다. */
function holdingsItem(row: Record<string, any>) {
  const marketValue = isObject(row.marketValue) ? row.marketValue : {};
  const profitLoss = isObject(row.profitLoss) ? row.profitLoss : {};
  const daily = isObject(row.dailyProfitLoss) ? row.dailyProfitLoss : {};
  const cost = isObject(row.cost) ? row.cost : {};
  return {
    symbol: value(row.symbol),
    name:
      typeof row.name === "string"
        ? truncateCodePoints(row.name, NAME_MAX_CHARS)
        : null,
    market: value(row.marketCountry),
    currency: value(row.currency),
    quantity: decimal(row.quantity),
    last_price: decimal(row.lastPrice),
    average_purchase_price: decimal(row.averagePurchasePrice),
    purchase_amount: decimal(marketValue.purchaseAmount),
    market_value: decimal(marketValue.amount),
    market_value_after_cost: decimal(marketValue.amountAfterCost),
    profit_loss: decimal(profitLoss.amount),
    profit_loss_after_cost: decimal(profitLoss.amountAfterCost),
    profit_loss_rate: decimal(profitLoss.rate),
    profit_loss_rate_after_cost: decimal(profitLoss.rateAfterCost),
    daily_profit_loss: decimal(daily.amount),
    daily_profit_loss_rate: decimal(daily.rate),
    commission: decimal(cost.commission),
    tax: decimal(cost.tax),
  };
}

export function registerAccountTools(register: RegisterTool, client: Tossinvest) {
  register("get_holdings", {}, { readOnlyHint: true }, async () => {
    const data = result(await client.request("/api/v1/holdings", { account: true }));
    return { total: holdingsTotal(data), items: objects(data.items).map(holdingsItem) };
  });

  register(
    "get_buying_power",
    { currency: z.string().default(""), symbol: z.string().optional() },
    { readOnlyHint: true },
    async ({ currency, symbol }) => {
      const code = currency.trim();
      if (!CURRENCIES.has(code))
        throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
      const stock = parseSymbol(symbol);
      // 같은 API 를 두 번 부르지 않는다. 매도 가능 수량은 종목을 주었을 때만 읽는다.
      const [power, sellable] = await Promise.all([
        client.request("/api/v1/buying-power", {
          query: new URLSearchParams({ currency: code }),
          account: true,
        }),
        stock === null
          ? null
          : client.request("/api/v1/sellable-quantity", {
              query: new URLSearchParams({ symbol: stock }),
              account: true,
            }),
      ]);
      const powerResult = result(power);
      const currencyValue = value(powerResult.currency);
      return {
        currency: typeof currencyValue === "string" ? currencyValue : code,
        cash_buying_power: decimal(powerResult.cashBuyingPower),
        sellable_quantity:
          sellable === null ? null : decimal(result(sellable).sellableQuantity),
      };
    },
  );

  register(
    "list_orders",
    {
      status: z.string().default(""),
      from: z.string().optional(),
      to: z.string().optional(),
      symbol: z.string().optional(),
    },
    { readOnlyHint: true },
    async (input) => {
      const query = orderQuery(input);
      const closed = query.get("status") === "CLOSED";
      // 끝난 주문은 한 쪽만 읽는다. 미체결은 API 가 커서와 limit 없이 전량을 준다.
      if (closed) query.set("limit", String(ORDERS_MAX));
      const data = result(
        await client.request("/api/v1/orders", { query, account: true }),
      );
      const orders = objects(data.orders);
      return {
        orders: orders.slice(0, ORDERS_MAX).map(orderRow),
        has_more:
          orders.length > ORDERS_MAX || (closed && data.hasNext === true),
      };
    },
  );
}
