/**
 * 답이 오는 동안 보내 Control Plane 이 쌓아 둔 글 하나다.
 *
 * <p>`messageId` 는 이미 대화에 steer 말풍선으로 보인 글의 그 메시지 번호다. `steering` 은 도는 실행에 끼워 넣는
 * 답을 기다리는 중이라 취소할 수 없다는 뜻이다(ADR-20261116 / run-steer). 옛 서버 응답에는 둘 다 없다.
 */
export type PendingMessage = {
  id: number;
  text: string;
  createdAt: string;
  messageId?: number | null;
  steering?: boolean;
};

/** 한 대화의 대기 줄이다. `held` 가 참이면 사용자가 「보내기」 를 누를 때까지 보내지 않는다. */
export type PendingQueue = { held: boolean; items: PendingMessage[] };

/**
 * 대기 글이 어디서 왔는지다. 입력창에 직접 쓴 글(`composer`)만 도는 실행에 끼워 넣어 본다.
 * 질문 카드 답, 넘기기의 「다 했어요」, 할 일 패널이 보낸 글은 `other` 다(ADR-20261116 / run-steer).
 */
export type QueueSource = "composer" | "other";

/** 보내기에 글을 따로 주지 않았으면 입력창의 글이다. 카드와 패널은 글을 인자로 준다. */
export function queueSourceOf(
  replacementText: string | undefined,
): QueueSource {
  return replacementText === undefined ? "composer" : "other";
}

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

/**
 * 글을 대기 줄에 더한다.
 *
 * @param steer 입력창에 직접 쓴 글이다. 참이면 서버가 도는 실행에 끼워 넣어 본다. 카드 답은 거짓이다
 */
export function enqueuePending(
  conversationId: string,
  text: string,
  steer: boolean,
): Promise<PendingResult<PendingQueue>> {
  return call<PendingQueue>(pendingPath(conversationId), {
    method: "POST",
    body: { text, steer },
  });
}

/**
 * 대기 칸에 그릴 글을 고른다.
 *
 * <p>멈추지 않은 줄의 말풍선 번호가 붙은 글은 이미 대화에 말풍선으로 보이고 곧 다음 turn 으로 가므로 그리지 않는다.
 * 멈춘 줄이면 사용자가 「보내기」 나 「취소」 를 골라야 하므로 그린다.
 */
export function visiblePendingItems(queue: PendingQueue): PendingMessage[] {
  if (queue.held) return queue.items;
  return queue.items.filter((item) => item.messageId == null);
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
