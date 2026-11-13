import { callConnectorApi } from "@/lib/connector-action";

/** 구매 상한의 API 이름이다. Control Plane 의 두 종류와 같다. */
export const PURCHASE_LIMIT_NAMES = ["order", "daily"] as const;

export type PurchaseLimitName = (typeof PURCHASE_LIMIT_NAMES)[number];

/** 1조 원 미만이다. Control Plane 이 받는 범위와 같다. */
export const PURCHASE_LIMIT_MAX = 999_999_999_999;

/**
 * 사용자의 구매 상한과 오늘 승인한 합계다(ADR-20261113 / purchase-checkout). 금액은 모두 원 단위 정수다.
 * 상한 기본값 0 은 주문서 준비를 받지 않는 닫힌 상태다.
 */
export type PurchaseLimits = {
  orderKrw: number;
  dailyKrw: number;
  spentTodayKrw: number;
  remainingTodayKrw: number;
};

export function isPurchaseLimitName(value: string): value is PurchaseLimitName {
  return (PURCHASE_LIMIT_NAMES as readonly string[]).includes(value);
}

/** 0 이상 1조 미만의 정수인가. */
export function isPurchaseLimitValue(value: unknown): value is number {
  return (
    typeof value === "number" &&
    Number.isSafeInteger(value) &&
    value >= 0 &&
    value <= PURCHASE_LIMIT_MAX
  );
}

/** 화면 입력 글을 원 단위 정수로 읽는다. 쉼표는 지운다. 읽을 수 없으면 null 이다. */
export function parsePurchaseLimitInput(draft: string): number | null {
  const plain = draft.replaceAll(",", "");
  if (!/^[0-9]{1,12}$/.test(plain)) return null;
  const value = Number(plain);
  return isPurchaseLimitValue(value) ? value : null;
}

export const readPurchaseLimits = () =>
  callConnectorApi<PurchaseLimits>("/api/purchase-limits");

export const savePurchaseLimit = (name: PurchaseLimitName, value: number) =>
  callConnectorApi<PurchaseLimits>(`/api/purchase-limits/${name}`, {
    method: "PUT",
    body: { value },
  });
