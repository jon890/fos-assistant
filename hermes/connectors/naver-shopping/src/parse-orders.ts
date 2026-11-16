/**
 * 결제 내역의 항목을 주문 확인 결과로 바꾸는 순수 함수다. 규칙은 docs/features/naver-shopping/design.md 의 「PR4 주문 확인(REQ14)」 이 갖는다.
 * 결과에는 상태 낱말, 금액 정수, 주문 시각만 싣는다. 이름, 주소, 상품명, 판매처, 주문 번호는 싣지 않는다.
 */
import { ORDER_SERVICE_TYPE } from "./fields.ts";
import { storeProductNo } from "./urls.ts";
import { list, won, type Json } from "./values.ts";

/**
 * 넘긴 시각에서 이만큼 앞선 주문까지 넘긴 뒤의 주문으로 본다. 0 이라 넘긴 시각 이후의 주문만 본다(spec DECISION27).
 * `date` 는 결제 시각이라고 본 가설이다. 넘기기 전에 연 주문서의 시각이면 진단 줄의 까닭이 `time` 으로 남고, 이 값을 앞당기려면 스펙을 함께 고친다.
 */
export const ORDER_TIME_GRACE_MS = 0;

/** fos-ctx 가 넣는 결제 넘기기 맥락(`_fos_payment`). Control Plane 이 그 대화의 마지막 결제 넘기기 줄에서 만든다. */
export type PaymentContext = {
  handedOffAtMs: number;
  finalAmountKrw: number;
  expectedTotalKrw: number;
  productNo: string;
};

const CONTEXT_KEYS = [
  "v",
  "handed_off_at_ms",
  "final_amount_krw",
  "expected_total_krw",
  "url",
];

/** 맥락을 정확한 키와 타입으로 읽는다. 없거나 모양이 틀리면 `null` 이다. 상품 주소는 `store` 갈래만 받는다. */
export function parsePaymentContext(value: Json): PaymentContext | null {
  if (value === null || typeof value !== "object" || Array.isArray(value))
    return null;
  const row = value as Record<string, Json>;
  const keys = Object.keys(row).sort();
  if (keys.join(",") !== [...CONTEXT_KEYS].sort().join(",")) return null;
  const at = row.handed_off_at_ms;
  const final = row.final_amount_krw;
  const expected = row.expected_total_krw;
  const productNo = storeProductNo(row.url);
  if (
    row.v !== 1 ||
    typeof at !== "number" ||
    !Number.isSafeInteger(at) ||
    at <= 0 ||
    typeof final !== "number" ||
    won(final) === null ||
    typeof expected !== "number" ||
    won(expected) === null ||
    productNo === null
  )
    return null;
  return {
    handedOffAtMs: at,
    finalAmountKrw: final,
    expectedTotalKrw: expected,
    productNo,
  };
}

/** 결제 내역 항목 하나에서 쓰는 값. */
type OrderItem = {
  productNo: string;
  dateMs: number;
  status: string | null;
  amounts: number[];
};

/** 칸을 읽지 못해 결과를 낼 수 없다. 도구가 진단 줄을 남기고 `PAGE_CHANGED` 로 끝낸다. */
export class OrdersChanged extends Error {
  constructor(readonly field: "orders" | "order_item") {
    super(field);
  }
}

const STATUS_WORD = /^[A-Z_]{1,40}$/;
const PRODUCT_NO = /^[0-9]{1,20}$/;

/** 상품 번호는 `product.no` 를, 없으면 상품 주소의 `/products/<번호>` 를 쓴다. */
function productNoOf(picked: Record<string, Json>): string | null {
  const no = picked.productNo;
  if (typeof no === "number" && Number.isSafeInteger(no) && no > 0)
    return String(no);
  if (typeof no === "string" && PRODUCT_NO.test(no)) return no;
  if (typeof picked.productUrl !== "string") return null;
  return (
    /\/products\/([0-9]{1,20})(?:[/?#]|$)/.exec(picked.productUrl)?.[1] ?? null
  );
}

/** 고정 식이 준 배열에서 `ORDER` 항목만 읽는다. 상품 번호나 시각을 읽지 못한 항목은 뺀다. */
export function readOrders(raw: Json): OrderItem[] {
  if (!Array.isArray(raw)) throw new OrdersChanged("orders");
  let orders = 0;
  const read: OrderItem[] = [];
  for (const value of raw) {
    if (value === null || typeof value !== "object" || Array.isArray(value))
      continue;
    const picked = value as Record<string, Json>;
    if (picked.serviceType !== ORDER_SERVICE_TYPE) continue;
    orders++;
    const productNo = productNoOf(picked);
    const dateMs = picked.date;
    if (
      productNo === null ||
      typeof dateMs !== "number" ||
      !Number.isSafeInteger(dateMs) ||
      dateMs <= 0
    )
      continue;
    read.push({
      productNo,
      dateMs,
      status:
        typeof picked.status === "string" && STATUS_WORD.test(picked.status)
          ? picked.status
          : null,
      amounts: list(picked.amounts)
        .map(won)
        .filter((amount): amount is number => amount !== null),
    });
  }
  // 주문이 있는데 하나도 읽지 못했으면 칸 위치가 바뀐 것이다. 지어내지 않는다.
  if (orders > 0 && read.length === 0) throw new OrdersChanged("order_item");
  return read;
}

/** 모델에게 주는 고정 안내 글. 화면 글이나 주소를 담지 않는다. */
export const ORDER_MESSAGES = {
  CONFIRMED:
    "결제 내역에서 결제 화면을 넘긴 뒤에 생긴 그 상품의 주문을 찾았고 결제 금액이 주문서의 최종 결제 금액과 같다. 사용자에게 주문이 확인됐다고 결제 금액과 함께 알린다.",
  AMOUNT_MISMATCH:
    "결제 화면을 넘긴 뒤에 생긴 그 상품의 주문은 있으나 결제 금액이 주문서의 최종 결제 금액과 다르다. 확인됐다고 하지 말고, 두 금액을 알린 뒤 사용자에게 「내 브라우저」의 네이버페이 결제 내역에서 직접 확인해 달라고 한다.",
  NOT_FOUND:
    "결제 화면을 넘긴 뒤에 생긴 그 상품의 주문을 결제 내역에서 찾지 못했다. 사용자에게 결제가 확인되지 않았다고 알리고, 결제했다면 「내 브라우저」의 네이버페이 결제 내역을 확인해 달라고 한다. 주문서를 다시 준비하지 않는다.",
  NO_PAYMENT_HANDOFF:
    "이 대화에서 결제 화면을 사람에게 넘긴 주문이 없어 주문을 확인하지 않았다. 결제 내역 화면도 열지 않았다. 주문서 준비부터 하지 않았다면 그렇게 알린다.",
} as const;

export type OrderCheck =
  | {
      status: "CONFIRMED" | "AMOUNT_MISMATCH";
      message: string;
      final_amount_krw: number;
      order: {
        status: string | null;
        paid_amount_krw: number | null;
        ordered_at: string;
      };
    }
  | { status: "NOT_FOUND"; message: string; final_amount_krw: number }
  | { status: "NO_PAYMENT_HANDOFF"; message: string };

/**
 * 넘긴 시각 이후의 같은 상품 주문을 고른다. 금액 후보 하나가 최종 결제 금액과 같은 것이 있으면 `CONFIRMED`,
 * 상품과 시각만 맞으면 `AMOUNT_MISMATCH`, 없으면 `NOT_FOUND` 다. 여럿이면 시각이 늦은 것이다.
 */
export function matchOrder(
  orders: OrderItem[],
  context: PaymentContext,
): OrderCheck {
  const since = context.handedOffAtMs - ORDER_TIME_GRACE_MS;
  const candidates = orders
    .filter(
      (order) => order.productNo === context.productNo && order.dateMs >= since,
    )
    .sort((a, b) => b.dateMs - a.dateMs);
  const paid = candidates.find((order) =>
    order.amounts.includes(context.finalAmountKrw),
  );
  const chosen = paid ?? candidates[0];
  if (!chosen)
    return {
      status: "NOT_FOUND",
      message: ORDER_MESSAGES.NOT_FOUND,
      final_amount_krw: context.finalAmountKrw,
    };
  const status = paid ? "CONFIRMED" : "AMOUNT_MISMATCH";
  return {
    status,
    message: ORDER_MESSAGES[status],
    final_amount_krw: context.finalAmountKrw,
    order: {
      status: chosen.status,
      paid_amount_krw: paid
        ? context.finalAmountKrw
        : (chosen.amounts[0] ?? null),
      ordered_at: new Date(chosen.dateMs).toISOString(),
    },
  };
}

/**
 * 확인되지 않은 까닭 낱말이다. 진단 줄에만 싣는다. `product` 는 같은 상품 주문이 없음, `time` 은 같은 상품 주문이 모두 넘긴 시각
 * 경계 앞, `amount` 는 금액 후보가 최종 결제 금액과 모두 다름이다. 확인됐으면 `null` 이다. 금액과 시각 칸의 가설을 실제 결제 뒤 맞출 때 본다.
 */
export function mismatchReason(
  orders: OrderItem[],
  context: PaymentContext,
): "product" | "time" | "amount" | null {
  const same = orders.filter((order) => order.productNo === context.productNo);
  if (same.length === 0) return "product";
  const since = context.handedOffAtMs - ORDER_TIME_GRACE_MS;
  const after = same.filter((order) => order.dateMs >= since);
  if (after.length === 0) return "time";
  return after.some((order) => order.amounts.includes(context.finalAmountKrw))
    ? null
    : "amount";
}
