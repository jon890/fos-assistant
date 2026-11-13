import type { CheckoutSummary } from "@/lib/checkout-summary";

/** 열지 못한 줄. 나머지 칸이 비고 카드는 승인 단추를 그리지 않는다. Node 테스트가 이 파일만 읽어 값은 여기에 둔다. */
const UNAVAILABLE_CHECKOUT: CheckoutSummary = {
  available: false,
  title: null,
  store: null,
  options: [],
  quantity: null,
  itemAmountKrw: null,
  shippingFeeKrw: null,
  expectedTotalKrw: null,
  regionalExtraFee: null,
  url: null,
  remainingTodayKrw: null,
};

/** 원 단위 금액이다. 0 이상 10억 미만 정수만 받는다. Control Plane 의 주문서 검증과 같은 범위다. */
function won(value: unknown): number {
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value) ||
    value < 0 ||
    value >= 1_000_000_000
  )
    throw new Error();
  return value;
}

function line(value: unknown, max: number): string {
  if (typeof value !== "string" || value.length === 0 || value.length > max)
    throw new Error();
  return value;
}

/** 상품 링크는 https 주소만 옮긴다. 아니면 그 칸만 비운다. */
function httpsUrl(value: unknown): string | null {
  if (typeof value !== "string" || value.length > 400) return null;
  try {
    return new URL(value).protocol === "https:" ? value : null;
  } catch {
    return null;
  }
}

function openCheckout(value: unknown): CheckoutSummary {
  if (value === null || typeof value !== "object" || Array.isArray(value))
    throw new Error();
  const item = value as Record<string, unknown>;
  if (item.available !== true) return { ...UNAVAILABLE_CHECKOUT, options: [] };
  if (!Array.isArray(item.options) || item.options.length > 3)
    throw new Error();
  const quantity = item.quantity;
  if (
    typeof quantity !== "number" ||
    !Number.isSafeInteger(quantity) ||
    quantity < 1 ||
    quantity > 99
  )
    throw new Error();
  if (typeof item.regionalExtraFee !== "boolean") throw new Error();
  return {
    available: true,
    title: line(item.title, 200),
    store: line(item.store, 50),
    options: item.options.map((option) => {
      if (option === null || typeof option !== "object") throw new Error();
      const entry = option as Record<string, unknown>;
      return { name: line(entry.name, 100), value: line(entry.value, 100) };
    }),
    quantity,
    itemAmountKrw: won(item.itemAmountKrw),
    shippingFeeKrw: won(item.shippingFeeKrw),
    expectedTotalKrw: won(item.expectedTotalKrw),
    regionalExtraFee: item.regionalExtraFee,
    url: httpsUrl(item.url),
    remainingTodayKrw:
      item.remainingTodayKrw === null || item.remainingTodayKrw === undefined
        ? null
        : typeof item.remainingTodayKrw === "number" &&
            Number.isSafeInteger(item.remainingTodayKrw)
          ? item.remainingTodayKrw
          : null,
  };
}

/**
 * 주문서 요약은 계약의 칸만 옮긴다. 열지 못한 줄과 모양이 계약과 다른 줄은 나머지 칸을 비운 `available: false` 로 낮춘다.
 * 던지면 한 줄 때문에 승인 목록 전체가 오류가 되어 다른 줄을 거절하는 길까지 막힌다. 낮춘 줄은 카드가 승인 단추 없이 거절만 남긴다.
 */
export function safeCheckout(value: unknown): CheckoutSummary {
  try {
    return openCheckout(value);
  } catch {
    return { ...UNAVAILABLE_CHECKOUT, options: [] };
  }
}
