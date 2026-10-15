import { callControlPlane } from "@/lib/control-plane";
import type { ControlPlaneResult } from "@/lib/control-plane-result";
import type {
  FinancialLimitStatus,
  LimitChangeSummary,
} from "@/lib/financial-summary";
import {
  FINANCIAL_LIMIT_NAMES,
  LIMIT_VALUE_PATTERN,
  type FinancialLimitItem,
  type FinancialLimits,
} from "@/lib/financial-limits";

/**
 * 주문 상한 응답을 계약의 칸만 옮긴다. 모양이 틀리면 던진다. 부른 쪽이 던짐을 고정 오류나 승인할 수 없는 줄로
 * 낮춘다. 값은 십진 글만 받는다.
 */
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error();
  return value as Record<string, unknown>;
}

function decimal(value: unknown): string {
  if (typeof value !== "string" || !LIMIT_VALUE_PATTERN.test(value))
    throw new Error();
  return value;
}

function text(value: unknown, max = 40): string {
  if (typeof value !== "string" || value.length > max) throw new Error();
  return value;
}

function currency(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  if (value !== "KRW" && value !== "USD") throw new Error();
  return value;
}

function name(value: unknown): FinancialLimitItem["name"] {
  const item = text(value);
  if (!(FINANCIAL_LIMIT_NAMES as readonly string[]).includes(item))
    throw new Error();
  return item as FinancialLimitItem["name"];
}

function byCurrency(value: unknown): Record<string, string> {
  const item = record(value);
  return { KRW: decimal(item.KRW), USD: decimal(item.USD) };
}

export function safeFinancialLimits(value: unknown): FinancialLimits {
  const item = record(value);
  if (typeof item.available !== "boolean" || !Array.isArray(item.limits))
    throw new Error();
  return {
    available: item.available,
    limits: item.limits.map((entry) => {
      const limit = record(entry);
      if (typeof limit.custom !== "boolean") throw new Error();
      return {
        name: name(limit.name),
        currency: currency(limit.currency),
        label: text(limit.label),
        value: decimal(limit.value),
        maximum: decimal(limit.maximum),
        custom: limit.custom,
      };
    }),
    spentToday: byCurrency(item.spentToday),
    remainingToday: byCurrency(item.remainingToday),
  };
}

/** 주문 카드의 상한이다. 없으면 null 이다. */
export function safeLimitStatus(value: unknown): FinancialLimitStatus | null {
  if (value === null || value === undefined) return null;
  const item = record(value);
  const unit = currency(item.currency);
  if (unit === null) throw new Error();
  return {
    currency: unit,
    orderLimit: decimal(item.orderLimit),
    quantityLimit: decimal(item.quantityLimit),
    dailyLimit: decimal(item.dailyLimit),
    spentToday: decimal(item.spentToday),
    remainingToday: decimal(item.remainingToday),
  };
}

const UNAVAILABLE_LIMIT_CHANGE: LimitChangeSummary = {
  available: false,
  name: "",
  currency: null,
  label: "",
  previous: "",
  requested: "",
  maximum: "",
  current: "",
};

/**
 * 상한 변경 줄의 요약이다. 모양이 계약과 다르면 던지지 않고 `available: false` 로 낮춘다. 던지면 한 줄 때문에 승인
 * 목록 전체가 오류가 된다. 낮춘 줄은 카드가 승인 단추 없이 거절만 남긴다.
 */
export function safeLimitChange(value: unknown): LimitChangeSummary {
  try {
    return openLimitChange(value);
  } catch {
    return { ...UNAVAILABLE_LIMIT_CHANGE };
  }
}

/** 주문 카드의 상한은 표시일 뿐이라 모양이 틀리면 그 표시만 비운다. */
export function safeLimitStatusOrNull(
  value: unknown,
): FinancialLimitStatus | null {
  try {
    return safeLimitStatus(value);
  } catch {
    return null;
  }
}

function openLimitChange(value: unknown): LimitChangeSummary {
  const item = record(value);
  return {
    available: true,
    name: name(item.name),
    currency: currency(item.currency),
    label: text(item.label),
    previous: decimal(item.previous),
    requested: decimal(item.requested),
    maximum: decimal(item.maximum),
    current: decimal(item.current),
  };
}

/** 상한 API 를 부르고 응답을 계약의 칸만 옮긴다. 모양이 틀리면 고정 오류로 돌린다. */
export async function financialLimitsCall(
  path: string,
  init: { method?: string; body?: unknown } = {},
): Promise<ControlPlaneResult<FinancialLimits>> {
  try {
    const result = await callControlPlane<unknown>(path, init);
    if (!result.ok) return result;
    return { ...result, data: safeFinancialLimits(result.data) };
  } catch {
    return { ok: false, status: 502, code: "INTERNAL_ERROR", message: "" };
  }
}
