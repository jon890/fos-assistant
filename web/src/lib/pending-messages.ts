/** 답이 오는 동안 보내 Control Plane 이 쌓아 둔 글 하나다. */
export type PendingMessage = { id: number; text: string; createdAt: string };

/** 한 대화의 대기 줄이다. `held` 가 참이면 사용자가 「보내기」 를 누를 때까지 보내지 않는다. */
export type PendingQueue = { held: boolean; items: PendingMessage[] };

export type PendingResult<T> =
  { ok: true; data: T } | { ok: false; code: string; message: string };

const FALLBACK_CODE = "INTERNAL_ERROR";
const FALLBACK_MESSAGE = "요청을 처리하지 못했어요.";

/**
 * 대기 메시지의 서버 라우트를 부른다.
 *
 * <p>요청이 닿지 못하면 `NETWORK` 로, 실패 응답이면 본문의 `code` 와 `message` 로 돌려준다. 본문이 없는
 * 성공(204)은 `data` 가 null 이다. 문구는 부르는 화면이 정한다.
 */
async function call<T>(
  path: string,
  init: { method: string; body?: unknown; signal?: AbortSignal },
): Promise<PendingResult<T>> {
  try {
    const response = await fetch(path, {
      method: init.method,
      headers:
        init.body === undefined
          ? undefined
          : { "Content-Type": "application/json" },
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      cache: "no-store",
      signal: init.signal,
    });
    const payload: unknown = await response.json().catch(() => null);
    if (response.ok) return { ok: true, data: payload as T };
    const failure = (payload ?? {}) as { code?: unknown; message?: unknown };
    return {
      ok: false,
      code: typeof failure.code === "string" ? failure.code : FALLBACK_CODE,
      message:
        typeof failure.message === "string"
          ? failure.message
          : FALLBACK_MESSAGE,
    };
  } catch {
    return { ok: false, code: "NETWORK", message: "" };
  }
}

function pendingPath(conversationId: string): string {
  return `/api/chat/conversations/${conversationId}/pending`;
}

export function fetchPendingQueue(
  conversationId: string,
  signal?: AbortSignal,
): Promise<PendingResult<PendingQueue>> {
  return call<PendingQueue>(pendingPath(conversationId), {
    method: "GET",
    signal,
  });
}

export function enqueuePending(
  conversationId: string,
  text: string,
): Promise<PendingResult<PendingQueue>> {
  return call<PendingQueue>(pendingPath(conversationId), {
    method: "POST",
    body: { text },
  });
}

export function cancelPending(
  conversationId: string,
  pendingId: number,
): Promise<PendingResult<null>> {
  return call<null>(`${pendingPath(conversationId)}/${pendingId}`, {
    method: "DELETE",
  });
}

export function sendPending(
  conversationId: string,
): Promise<PendingResult<PendingQueue>> {
  return call<PendingQueue>(`${pendingPath(conversationId)}/send`, {
    method: "POST",
  });
}
