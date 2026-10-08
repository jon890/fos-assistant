import { z } from "zod";
import { NAME_MAX_CHARS, ORDERS_MAX } from "./constants.ts";
import { TossinvestError } from "./errors.ts";
import { decimalValue as decimal, serviceValue as value, truncateCodePoints } from "./values.ts";
import type { Tossinvest } from "./client.ts";
import { writeOrdersFile } from "./order-file.ts";
import { isObject, optional, orderQuery, orderRow, parseSymbol } from "./orders.ts";
import type { RegisterTool } from "./tool-registration.ts";

const CURRENCIES = new Set(["KRW", "USD"]);

/** 합계 금액 `{krw, usd}` 를 옮긴다. `usd` 는 API 가 null 로 줄 수 있다. */
const money = (raw: unknown) =>
  isObject(raw) ? { krw: decimal(raw.krw), usd: decimal(raw.usd) } : null;

const objects = (raw: unknown): Record<string, any>[] =>
  Array.isArray(raw) ? raw.filter(isObject) : [];

const result = (data: any): Record<string, any> =>
  isObject(data?.result) ? data.result : {};

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
      output: z.string().optional(),
    },
    { readOnlyHint: true },
    async (input) => {
      const query = orderQuery(input);
      // 파일 출력은 `file` 하나뿐이다. 다른 값은 요청 없이 거절한다.
      const output = optional(input.output);
      if (output !== null && output !== "file")
        throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
      if (output === "file")
        return writeOrdersFile(client, client.env.TOSSINVEST_OUTPUT_DIR ?? "", query);
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
