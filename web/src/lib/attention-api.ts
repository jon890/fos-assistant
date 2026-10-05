import type { AttentionCardKey } from "@/lib/attention";
import { memoryRequest, type MemoryApiResult } from "@/lib/memory-api";

/** 지금 화면과 사이드바가 부르는 요청이다. 응답을 읽고 실패를 다루는 일은 부르는 쪽이 맡는다. 건수 읽기만 0 으로 대신한다. */

/**
 * 지금 볼 것이 바뀌었을 수 있다는 브라우저 사건이다. 사이드바 「지금 볼 것」 이 받아 수를 다시 읽는다.
 * 지금 화면 안의 동작은 경로를 바꾸지 않아, 화면을 옮길 때 다시 읽는 효과가 돌지 않기 때문이다.
 */
export const ATTENTION_CHANGED_EVENT = "attention-changed";

const CONTROL_FAILED = "바꾸지 못했어요. 다시 시도해 주세요.";

/** 지금 볼 것의 건수(`nowCount`)를 읽는다. 읽지 못하거나 수가 아니면 0 이다. */
export async function readNowCount(): Promise<number> {
  try {
    const response = await fetch("/api/attention/summary", {
      cache: "no-store",
    });
    if (!response.ok) return 0;
    const summary = (await response.json()) as { nowCount?: unknown };
    return typeof summary.nowCount === "number" ? summary.nowCount : 0;
  } catch {
    return 0;
  }
}

async function control(
  path: string,
  body: Record<string, string>,
): Promise<MemoryApiResult<null>> {
  const result = await memoryRequest<null>(path, CONTROL_FAILED, {
    method: "POST",
    body,
  });
  if (result.ok) window.dispatchEvent(new Event(ATTENTION_CHANGED_EVENT));
  return result;
}

/** 그 카드에서 항목을 숨긴다. 항목의 상태가 바뀌면 다시 보인다. */
export function hideItem(
  card: AttentionCardKey,
  itemKey: string,
  stateKey: string,
): Promise<MemoryApiResult<null>> {
  return control("/api/attention/hide", { card, itemKey, stateKey });
}

/** 그 카드에서 항목을 `until`(ISO 시각)까지 미룬다. */
export function snoozeItem(
  card: AttentionCardKey,
  itemKey: string,
  until: string,
): Promise<MemoryApiResult<null>> {
  return control("/api/attention/snooze", { card, itemKey, until });
}

/** 그 카드의 숨기기와 미루기를 지운다. */
export function restoreItem(
  card: AttentionCardKey,
  itemKey: string,
): Promise<MemoryApiResult<null>> {
  return control("/api/attention/restore", { card, itemKey });
}

/** 항목을 열었거나(`OPENED`) 동작했다는(`ACTED`) 사건을 남긴다. 남기지 못해도 알리지 않는다. */
export function recordAttentionEvent(
  itemKey: string,
  stateKey: string,
  type: "OPENED" | "ACTED",
): void {
  void fetch("/api/attention/events", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ itemKey, stateKey, type }),
    // 사건을 남기는 동안 다른 화면으로 옮겨도 요청이 끊기지 않게 한다.
    keepalive: true,
  }).catch(() => {});
}
