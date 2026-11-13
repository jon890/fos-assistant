import { callControlPlane } from "@/lib/control-plane";
import type { ControlPlaneResult } from "@/lib/control-plane-result";
import {
  isPurchaseLimitValue,
  type PurchaseLimits,
} from "@/lib/purchase-limits";

function won(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0)
    throw new Error();
  return value;
}

function limit(value: unknown): number {
  if (!isPurchaseLimitValue(value)) throw new Error();
  return value;
}

/** 구매 상한 응답을 계약의 칸만 옮긴다. 모양이 틀리면 던진다. */
export function safePurchaseLimits(value: unknown): PurchaseLimits {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error();
  const item = value as Record<string, unknown>;
  return {
    orderKrw: limit(item.orderKrw),
    dailyKrw: limit(item.dailyKrw),
    spentTodayKrw: won(item.spentTodayKrw),
    remainingTodayKrw: won(item.remainingTodayKrw),
  };
}

/** 구매 상한 API 를 부르고 응답을 계약의 칸만 옮긴다. 모양이 틀리면 고정 오류로 돌린다. */
export async function purchaseLimitsCall(
  path: string,
  init: { method?: string; body?: unknown } = {},
): Promise<ControlPlaneResult<PurchaseLimits>> {
  try {
    const result = await callControlPlane<unknown>(path, init);
    if (!result.ok) return result;
    return { ...result, data: safePurchaseLimits(result.data) };
  } catch {
    return { ok: false, status: 502, code: "INTERNAL_ERROR", message: "" };
  }
}
