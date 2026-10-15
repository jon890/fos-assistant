import { callConnectorApi } from "@/lib/connector-action";

/** 주문 상한의 API 이름이다. Control Plane 의 다섯 종류와 같다. */
export const FINANCIAL_LIMIT_NAMES = [
  "order_amount_krw",
  "order_amount_usd",
  "order_quantity",
  "daily_amount_krw",
  "daily_amount_usd",
] as const;

export type FinancialLimitName = (typeof FINANCIAL_LIMIT_NAMES)[number];

/** 0 이상, 소수 6자리까지의 십진 글이다. Control Plane 이 받는 모양과 같다. */
export const LIMIT_VALUE_PATTERN = /^[0-9]{1,14}(\.[0-9]{1,6})?$/;

export type FinancialLimitItem = {
  name: FinancialLimitName;
  currency: string | null;
  label: string;
  value: string;
  maximum: string;
  custom: boolean;
};

/**
 * 사용자의 주문 상한과 오늘 쓴 금액이다. `available` 이 거짓이면 이 사용자에게 열린 계좌가 없어 바꿀 수 없다.
 * `spentToday` 와 `remainingToday` 는 통화(KRW, USD)마다의 값이다.
 */
export type FinancialLimits = {
  available: boolean;
  limits: FinancialLimitItem[];
  spentToday: Record<string, string>;
  remainingToday: Record<string, string>;
};

export function isFinancialLimitName(
  value: string,
): value is FinancialLimitName {
  return (FINANCIAL_LIMIT_NAMES as readonly string[]).includes(value);
}

/** 최대치를 넘는가. 두 값 모두 십진 글이다. */
export function exceedsMaximum(value: string, maximum: string): boolean {
  return Number(value) > Number(maximum);
}

export const readFinancialLimits = () =>
  callConnectorApi<FinancialLimits>("/api/financial-limits");

/** `value` 가 null 이면 운영 최대치를 따르게 한다. */
export const saveFinancialLimit = (
  name: FinancialLimitName,
  value: string | null,
) =>
  callConnectorApi<FinancialLimits>(`/api/financial-limits/${name}`, {
    method: "PUT",
    body: { value },
  });
