import { randomUUID } from "node:crypto";
import { SCHEMAS } from "./api-contract.ts";
import type { Tossinvest } from "./client.ts";
import { validAccountSeq } from "./constants.ts";
import { orderSegment } from "./conditional-read-tools.ts";
import { officialFields } from "./official-response.ts";
import {
  CONDITIONAL_MODEL_SCHEMAS,
  CONDITIONAL_OPERATIONS,
  checkDetail,
  checkOcoPrice,
  conditionalBody,
  type ConditionalTool,
} from "./conditional-body.ts";
import {
  canonicalDecimal,
  country,
  reject,
  type Args,
} from "./trading-body.ts";

export const conditionalPath = (id: string) =>
  `/api/v1/conditional-orders/${orderSegment(id)}`;

/** 공개 조회 등록에 기대지 않는 현재 원주문 조회다. 공식 필드만 투영하고 null 과 부재를 보존한다. */
export async function readExpectedConditionalOrder(
  client: Tossinvest,
  id: string,
) {
  const envelope = await client.request(conditionalPath(id), { account: true });
  const detail = officialFields(
    envelope.result,
    SCHEMAS.ConditionalOrderDetailResponse,
  );
  checkDetail(detail);
  if (detail.conditionalOrderId !== id) reject();
  return detail as Args;
}

/** OCO 의 현재가다. 값은 decimal 문자열 그대로 비교한다. */
export async function readLastPrice(client: Tossinvest, symbol: string) {
  const prices = await client.request("/api/v1/prices", {
    query: new URLSearchParams({ symbols: symbol }),
  });
  const row = prices.result.find((item: Args) => item.symbol === symbol);
  if (!row) reject();
  return row.lastPrice as string;
}

// normalization 은 이 경로 순서대로 실제 표기 변경만 남긴다.
const NORMALIZED = [
  ["symbol"],
  ["quantity"],
  ["first", "triggerPrice"],
  ["first", "orderPrice"],
  ["second", "triggerPrice"],
  ["second", "orderPrice"],
] as const;

/** prepare 만 정규화와 UUID 를 만든다. 승인 뒤 같은 과정을 되풀이하지 않는다. */
export async function prepareConditionalTrade(
  client: Tossinvest,
  tool: ConditionalTool,
  raw: Args,
) {
  const schema = CONDITIONAL_MODEL_SCHEMAS[tool];
  if (!schema || !schema.safeParse(raw).success) reject();
  const input: Args = structuredClone(raw);
  delete input.confirmHighValueOrder;
  const normalization: Array<{ field: string; before: string; after: string }> =
    [];
  for (const path of NORMALIZED) {
    const holder = path.length === 1 ? input : input[path[0]];
    const key = path[path.length - 1]!;
    if (!holder || !Object.hasOwn(holder, key)) continue;
    const before = holder[key];
    const after =
      key === "symbol" ? before.toUpperCase() : canonicalDecimal(before);
    if (before !== after)
      normalization.push({ field: path.join("."), before, after });
    holder[key] = after;
  }

  const account = client.env.TOSSINVEST_ACCOUNT_SEQ;
  if (!account || !validAccountSeq(account)) reject();
  const create = tool === "create_conditional_order";
  const original = create
    ? null
    : await readExpectedConditionalOrder(client, input.conditionalOrderId);
  const symbol: string = original?.symbol ?? input.symbol;
  const [accounts, stocks, lastPrice] = await Promise.all([
    client.request("/api/v1/accounts"),
    client.request("/api/v1/stocks", {
      query: new URLSearchParams({ symbols: symbol }),
    }),
    tool !== "cancel_conditional_order" && input.type === "OCO"
      ? readLastPrice(client, symbol)
      : null,
  ]);
  const accountRow = accounts.result.find(
    (row: Args) => row.accountSeq === account,
  );
  const stock = stocks.result.find((row: Args) => row.symbol === symbol);
  if (!accountRow || !stock) reject();
  const region = country(stock.market, stock.currency);
  const digits = accountRow.accountNo.replace(/\D/g, "");
  if (digits.length < 4) reject();
  const scope = {
    account_seq: account,
    market: stock.market,
    currency: stock.currency,
  };
  const executionArgs: Args = create
    ? { ...input, ...scope, clientOrderId: randomUUID() }
    : { ...input, ...scope, expected_conditional_order: original };
  conditionalBody(tool, executionArgs);
  if (lastPrice !== null) checkOcoPrice(input, lastPrice);

  // 취소는 원주문의 조회 leg 를 표시한다. 유효기간 부재는 표시에서만 null 이다.
  const shown = tool === "cancel_conditional_order" ? original! : input;
  const summary = {
    orderKind: "CONDITIONAL",
    account: digits.slice(-4),
    operation: CONDITIONAL_OPERATIONS[tool],
    conditionalOrderId: original?.conditionalOrderId ?? null,
    symbol,
    market: region,
    currency: stock.currency,
    type: shown.type,
    quantity: shown.quantity,
    orderType: shown.orderType,
    expireDate: shown.expireDate ?? null,
    first: shown.first,
    second: shown.second ?? null,
    original,
    normalization,
  };
  if (client.env.TOSSINVEST_ACCOUNT_SEQ !== account) reject();
  return { v: 1, executionArgs, summary };
}
