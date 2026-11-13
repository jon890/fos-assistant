/** 주문서 준비 승인 줄에서 고른 옵션 하나다. */
export type CheckoutOption = { name: string; value: string };

/**
 * 주문서 준비 승인 줄의 카드 요약이다(ADR-20261113 / purchase-checkout). Control Plane 이 저장해 검증한 요약이고 모델이 낸 인자가 아니다.
 * `available` 이 거짓이면 저장한 내용을 열지 못한 줄이라 나머지 칸이 비었고, 그 줄은 승인할 수 없다.
 * 배송지와 결제 수단 칸은 없다(spec DECISION8). 금액은 원 단위 정수다. `remainingTodayKrw` 는 읽을 때 계산한 오늘 남은 구매 한도이고 읽지 못하면 null 이다.
 */
export type CheckoutSummary = {
  available: boolean;
  title: string | null;
  store: string | null;
  options: CheckoutOption[];
  quantity: number | null;
  itemAmountKrw: number | null;
  shippingFeeKrw: number | null;
  expectedTotalKrw: number | null;
  regionalExtraFee: boolean | null;
  url: string | null;
  remainingTodayKrw: number | null;
};

export const UNAVAILABLE_CHECKOUT: CheckoutSummary = {
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
