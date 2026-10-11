import { RESPONSE_MAX_BYTES } from "./constants.ts";
import { TossinvestError } from "./errors.ts";

/** HTTP 상태와 JSON 본문이다. 본문이 JSON 이 아니면 `data` 가 undefined 다. */
export interface Exchange {
  status: number;
  ok: boolean;
  data: any;
  empty: boolean;
  retryAfterMs: number;
}
/** 본문을 상한까지만 읽는다. 상한을 넘으면 남은 본문을 받지 않도록 읽기를 끊고 UNAVAILABLE 을 던진다. */
export async function bounded(response: Response): Promise<Uint8Array> {
  const length = Number(response.headers.get("content-length") ?? 0);
  if (Number.isFinite(length) && length > RESPONSE_MAX_BYTES) {
    await response.body?.cancel();
    throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
  }
  if (!response.body) return new Uint8Array();
  const reader = response.body.getReader();
  const parts: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      total += next.value.byteLength;
      if (total > RESPONSE_MAX_BYTES) {
        await reader.cancel();
        throw new TossinvestError("TOSSINVEST_UNAVAILABLE");
      }
      parts.push(next.value);
    }
  } finally {
    reader.releaseLock();
  }
  const result = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.byteLength;
  }
  return result;
}

export const bodyCode = (data: any) =>
  typeof data?.error?.code === "string" ? data.error.code : "";
/** `AbortSignal.timeout` 이 끊은 읽기는 런타임에 따라 TimeoutError 나 AbortError 로 온다. */
export const isTimeout = (error: unknown) =>
  error instanceof Error &&
  (error.name === "TimeoutError" || error.name === "AbortError");
/** 토큰 발급 실패를 공통 어휘로 옮긴다. 본문 `error` 를 상태보다 먼저 본다. */
export function tokenErrorCode(exchange: Exchange): string {
  const { status, data } = exchange;
  if (status === 403 && data?.error === "access_denied")
    return "TOSSINVEST_IP_NOT_ALLOWED";
  if (status === 400 || status === 401 || status === 403)
    return "TOSSINVEST_UNAUTHORIZED";
  if (status === 429) return "TOSSINVEST_RATE_LIMITED";
  return "TOSSINVEST_UNAVAILABLE";
}

/** API 실패를 공통 어휘로 옮긴다. 다시 받을 401 은 호출하는 쪽이 먼저 가려낸다. */
export function apiErrorCode(exchange: Exchange): string {
  const { status } = exchange;
  const code = bodyCode(exchange.data);
  if (code === "ip-not-allowed") return "TOSSINVEST_IP_NOT_ALLOWED";
  if (code === "account-not-found") return "TOSSINVEST_ACCOUNT_NOT_FOUND";
  if (status === 401) return "TOSSINVEST_UNAUTHORIZED";
  if (status === 403) return "TOSSINVEST_FORBIDDEN";
  if (status === 400) return "TOSSINVEST_INVALID_INPUT";
  if (status === 404 && ["stock-not-found", "sector-not-found"].includes(code))
    return "TOSSINVEST_INVALID_INPUT";
  if (status === 429) return "TOSSINVEST_RATE_LIMITED";
  return "TOSSINVEST_UNAVAILABLE";
}
