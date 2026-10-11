import { randomUUID } from "node:crypto";
import type { Tossinvest } from "./client.ts";
import { validAccountSeq } from "./constants.ts";
import {
  MODEL_SCHEMAS,
  TRADE_OPERATIONS,
  canonicalDecimal,
  country,
  expectedOrder,
  reject,
  tradingBody,
  type Args,
  type TradeTool,
} from "./trading-body.ts";

export const orderPath = (id: string) =>
  `/api/v1/orders/${encodeURIComponent(id)}`;

/** 공개 get_order 등록에 기대지 않는 내부 원주문 조회다. */
export async function readExpectedOrder(client: Tossinvest, id: string) {
  const response = await client.request(orderPath(id), { account: true });
  const expected = expectedOrder(response.result);
  if (expected.orderId !== id) reject();
  return expected;
}

/** prepare만 정규화·기본값·UUID를 만든다. 승인 뒤 같은 과정을 되풀이하지 않는다. */
export async function prepareTrade(
  client: Tossinvest,
  tool: TradeTool,
  raw: Args,
) {
  const schema = MODEL_SCHEMAS[tool];
  if (!schema || !schema.safeParse(raw).success) reject();
  if (
    tool !== "cancel_order" &&
    (raw.orderType === "LIMIT") !== Object.hasOwn(raw, "price")
  )
    reject();
  if (
    tool === "create_order" &&
    Object.hasOwn(raw, "quantity") === Object.hasOwn(raw, "orderAmount")
  )
    reject();
  const input: Args = { ...raw };
  delete input.confirmHighValueOrder;
  const normalization: Array<{
    field: string;
    before: string | null;
    after: string;
  }> = [];
  const normalize = (field: string, after: string) => {
    if (input[field] !== after)
      normalization.push({ field, before: input[field] ?? null, after });
    input[field] = after;
  };
  if (tool === "create_order") {
    normalize("symbol", input.symbol.toUpperCase());
    normalize("timeInForce", input.timeInForce ?? "DAY");
  }
  for (const field of ["quantity", "orderAmount", "price"])
    if (Object.hasOwn(input, field))
      normalize(field, canonicalDecimal(input[field]));

  const account = client.env.TOSSINVEST_ACCOUNT_SEQ;
  if (!account || !validAccountSeq(account)) reject();
  const original =
    tool === "create_order"
      ? null
      : await readExpectedOrder(client, input.orderId);
  const symbol = original?.symbol ?? input.symbol;
  const [accounts, stocks] = await Promise.all([
    client.request("/api/v1/accounts"),
    client.request("/api/v1/stocks", {
      query: new URLSearchParams({ symbols: symbol }),
    }),
  ]);
  const accountRow = accounts.result.find(
    (row: Args) => row.accountSeq === account,
  );
  const stock = stocks.result.find((row: Args) => row.symbol === symbol);
  if (!accountRow || !stock) reject();
  country(stock.market, stock.currency);
  if (original && original.currency !== stock.currency) reject();
  const digits = accountRow.accountNo.replace(/\D/g, "");
  if (digits.length < 4) reject();
  const scope = {
    account_seq: account,
    market: stock.market,
    currency: stock.currency,
  };
  const executionArgs: Args =
    tool === "create_order"
      ? { ...input, clientOrderId: randomUUID(), ...scope }
      : { ...input, ...scope, expected_order: original };
  tradingBody(tool, executionArgs);

  const originalDisplay =
    original === null
      ? null
      : {
          orderId: original.orderId,
          symbol: original.symbol,
          market: stock.market,
          currency: original.currency,
          side: original.side,
          quantity: original.quantity,
          orderAmount: original.orderAmount,
          price: original.price,
          orderType: original.orderType,
          timeInForce: original.timeInForce,
          status: original.status,
          filledQuantity: original.execution.filledQuantity,
        };
  const display = tool === "cancel_order" ? original! : input;
  const summary = {
    account: digits.slice(-4),
    symbol,
    market: stock.market,
    currency: stock.currency,
    side: original?.side ?? input.side,
    quantity: display.quantity ?? original?.quantity ?? null,
    orderAmount: display.orderAmount ?? original?.orderAmount ?? null,
    price: display.price ?? null,
    orderType: display.orderType,
    timeInForce: original?.timeInForce ?? input.timeInForce,
    operation: TRADE_OPERATIONS[tool],
    orderId: original?.orderId ?? null,
    original: originalDisplay,
    normalization,
  };
  if (client.env.TOSSINVEST_ACCOUNT_SEQ !== account) reject();
  return { v: 1, executionArgs, summary };
}
