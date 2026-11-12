import { z } from "zod";
import { SYMBOL } from "./constants.ts";
import {
  canonicalDecimal,
  country,
  reject,
  type Args,
} from "./trading-body.ts";

/** 조건주문 쓰기 도구와 manifest operation 이다. 운영 server 는 이 도구를 등록하지 않는다. */
export const CONDITIONAL_OPERATIONS = {
  create_conditional_order: "CREATE",
  modify_conditional_order: "MODIFY",
  cancel_conditional_order: "CANCEL",
} as const;
export type ConditionalTool = keyof typeof CONDITIONAL_OPERATIONS;
export const CONDITIONAL_TOOLS = Object.keys(CONDITIONAL_OPERATIONS) as [
  ConditionalTool,
  ...ConditionalTool[],
];

const text = z.string().min(1);
const decimal = text.max(30).regex(/^[0-9]+(?:\.[0-9]+)?$/);
/** 조건 조회의 orderSegment 와 같은 한 segment 규칙이다. 잘못된 surrogate 도 거절한다. */
const opaqueId = text
  .max(256)
  .regex(/^[^\s/\\?#%:\u0000-\u001f\u007f-\u009f]+$/u)
  .refine((value) => value !== "." && value !== ".." && value.isWellFormed());
const date = text.regex(/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/);
const side = z.enum(["BUY", "SELL"]);
const orderType = z.enum(["LIMIT", "MARKET"]);
const symbol = text.max(32).regex(SYMBOL);
const leg = z.strictObject({
  orderSide: side,
  triggerPrice: decimal,
  orderPrice: decimal.optional(),
});
const write = {
  type: z.enum(["SINGLE", "OCO", "OTO"]),
  quantity: decimal,
  orderType,
  expireDate: date,
  first: leg,
  second: leg.optional(),
};
const consent = { confirmHighValueOrder: z.literal(false).optional() };

/** 모델이 넣는 인자다. 계좌·시장·통화·멱등키·현재 원주문은 받지 않는다. */
export const CONDITIONAL_MODEL_SCHEMAS = {
  create_conditional_order: z.strictObject({ symbol, ...write, ...consent }),
  modify_conditional_order: z.strictObject({
    conditionalOrderId: opaqueId,
    ...write,
    ...consent,
  }),
  cancel_conditional_order: z.strictObject({ conditionalOrderId: opaqueId }),
};

const status = [
  "WATCHING",
  "PAUSED",
  "ORDERING",
  "ORDERED",
  "COMPLETED",
  "EXPIRED",
] as const;
const detailLeg = z.strictObject({
  type: z.enum(["STOP", "PROFIT_RATE"]),
  status: z.enum([...status, "HOLDING", "CANCELED"]),
  orderSide: side,
  triggerPrice: decimal.nullable().optional(),
  targetProfitRate: text
    .max(30)
    .regex(/^-?[0-9]+(?:\.[0-9]+)?$/)
    .nullable()
    .optional(),
  orderPrice: decimal.nullable().optional(),
  triggeredOrderId: opaqueId.nullable().optional(),
});
/** 공식 ConditionalOrderDetailResponse 투영이다. null 과 부재를 구분한다. */
export const conditionalDetailSchema = z.strictObject({
  conditionalOrderId: opaqueId,
  type: write.type,
  status: z.enum(status),
  symbol,
  market: z.enum(["KR", "US"]),
  quantity: decimal,
  orderType,
  expireDate: date.optional(),
  first: detailLeg,
  second: detailLeg.nullable().optional(),
  createdAt: text
    .max(64)
    .regex(
      /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})$/,
    ),
});
const scope = {
  account_seq: text,
  market: z.enum([
    "KOSPI",
    "KOSDAQ",
    "KR_ETC",
    "NYSE",
    "NASDAQ",
    "AMEX",
    "US_ETC",
  ]),
  currency: z.enum(["KRW", "USD"]),
};
const uuid = text.regex(
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
);
/** 승인할 실행 원문이다. prepare 가 만든 값만 받고 coercion·default 를 두지 않는다. */
export const CONDITIONAL_EXECUTION_SCHEMAS = {
  create_conditional_order: z.strictObject({
    symbol,
    ...write,
    ...scope,
    clientOrderId: uuid,
  }),
  modify_conditional_order: z.strictObject({
    conditionalOrderId: opaqueId,
    ...write,
    ...scope,
    expected_conditional_order: conditionalDetailSchema,
  }),
  cancel_conditional_order: z.strictObject({
    conditionalOrderId: opaqueId,
    ...scope,
    expected_conditional_order: conditionalDetailSchema,
  }),
};

/** canonical decimal 두 개를 Number 변환 없이 비교한다. */
export function compareDecimal(left: string, right: string): number {
  const [li, lf = ""] = canonicalDecimal(left, true).split(".") as [
    string,
    string?,
  ];
  const [ri, rf = ""] = canonicalDecimal(right, true).split(".") as [
    string,
    string?,
  ];
  if (li.length !== ri.length) return li.length - ri.length;
  const width = Math.max(lf.length, rf.length);
  const a = li + lf.padEnd(width, "0");
  const b = ri + rf.padEnd(width, "0");
  return a === b ? 0 : a < b ? -1 : 1;
}

/** 실제 날짜이며 KST 오늘부터 달력상 1년 뒤(없으면 그 달 말일)까지다. 만료일을 바꾸지 않는다. */
export function checkExpireDate(value: string, now = Date.now()) {
  const parsed = new Date(`${value}T00:00:00Z`);
  if (
    Number.isNaN(parsed.getTime()) ||
    parsed.toISOString().slice(0, 10) !== value
  )
    reject();
  const today = new Date(now + 9 * 3600_000).toISOString().slice(0, 10);
  const [year, month, day] = today.split("-").map(Number) as [
    number,
    number,
    number,
  ];
  const last = new Date(Date.UTC(year + 1, month, 0)).getUTCDate();
  const pad = (n: number) => String(n).padStart(2, "0");
  const limit = `${year + 1}-${pad(month)}-${pad(Math.min(day, last))}`;
  if (value < today || value > limit) reject();
}

/** 원주문 투영의 schema 밖 관계를 확인한다. 상태만으로 거절하지 않는다. */
export function checkDetail(detail: Args) {
  if (!conditionalDetailSchema.safeParse(detail).success) reject();
  canonicalDecimal(detail.quantity);
  if (
    (detail.type === "SINGLE") !==
    (detail.second === undefined || detail.second === null)
  )
    reject();
  if (detail.second && detail.second.type !== detail.first.type) reject();
  for (const item of [detail.first, detail.second])
    for (const field of ["triggerPrice", "orderPrice"])
      if (item?.[field] !== undefined && item?.[field] !== null)
        canonicalDecimal(item[field]);
}

/** canonical 값이어야 하며 KR 은 원 단위, US 는 1달러 미만 4자리·이상 2자리다. */
function checkPrice(value: string, region: "KR" | "US") {
  if (canonicalDecimal(value) !== value) reject();
  const [integer, fraction = ""] = value.split(".");
  if (fraction.length > (region === "KR" ? 0 : integer === "0" ? 4 : 2))
    reject();
}

/** 새 쓰기의 공식 분기와 정확한 decimal 관계다. */
export function checkWrite(args: Args, region: "KR" | "US") {
  if (canonicalDecimal(args.quantity) !== args.quantity) reject();
  const legs = [
    args.first,
    ...(Object.hasOwn(args, "second") ? [args.second] : []),
  ];
  if ((args.type === "SINGLE") !== (legs.length === 1)) reject();
  if (args.type !== "SINGLE") {
    if (args.orderType !== "LIMIT" || args.second.orderSide !== "SELL")
      reject();
    if (args.first.orderSide !== (args.type === "OCO" ? "SELL" : "BUY"))
      reject();
  }
  for (const item of legs) {
    if (canonicalDecimal(item.triggerPrice) !== item.triggerPrice) reject();
    if ((args.orderType === "LIMIT") !== Object.hasOwn(item, "orderPrice"))
      reject();
    if (item.orderPrice !== undefined) checkPrice(item.orderPrice, region);
  }
  if (
    args.type === "OCO" &&
    compareDecimal(args.first.triggerPrice, args.second.triggerPrice) <= 0
  )
    reject();
  checkExpireDate(args.expireDate);
}

/** OCO 는 first.triggerPrice > 현재가 > second.triggerPrice 여야 한다. */
export function checkOcoPrice(args: Args, lastPrice: unknown) {
  if (args.type !== "OCO") return;
  if (typeof lastPrice !== "string") reject();
  if (
    compareDecimal(args.first.triggerPrice, lastPrice as string) <= 0 ||
    compareDecimal(lastPrice as string, args.second.triggerPrice) <= 0
  )
    reject();
}

const legBody = (item: Args) => ({
  orderSide: item.orderSide,
  triggerPrice: item.triggerPrice,
  ...(item.orderPrice !== undefined ? { orderPrice: item.orderPrice } : {}),
});

/**
 * 승인된 실행 원문에서 공식 본문 칸만 고른다. 기본값이나 값을 보충하지 않으며 false 동의만 명시한다.
 * 취소는 본문이 없다. scope·시장·통화·현재 원주문과 정정의 symbol·clientOrderId 는 보내지 않는다.
 */
export function conditionalBody(tool: ConditionalTool, args: Args): Args {
  const schema = CONDITIONAL_EXECUTION_SCHEMAS[tool];
  if (!schema || !schema.safeParse(args).success) reject();
  const region = country(args.market, args.currency);
  if (tool !== "create_conditional_order") {
    const expected = args.expected_conditional_order;
    checkDetail(expected);
    if (
      expected.conditionalOrderId !== args.conditionalOrderId ||
      expected.market !== region
    )
      reject();
  }
  if (tool === "cancel_conditional_order") return {};
  const create = tool === "create_conditional_order";
  if (create && args.symbol !== args.symbol.toUpperCase()) reject();
  checkWrite(args, region);
  return {
    ...(create ? { symbol: args.symbol } : {}),
    type: args.type,
    quantity: args.quantity,
    orderType: args.orderType,
    ...(create ? { clientOrderId: args.clientOrderId } : {}),
    expireDate: args.expireDate,
    first: legBody(args.first),
    ...(Object.hasOwn(args, "second") ? { second: legBody(args.second) } : {}),
    confirmHighValueOrder: false,
  };
}
