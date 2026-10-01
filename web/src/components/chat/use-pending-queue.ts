"use client";

import { useEffect, useRef, useState } from "react";
import {
  cancelPending,
  enqueuePending,
  fetchPendingQueue,
  sendPending,
  type PendingQueue,
  type PendingResult,
} from "@/lib/pending-messages";

const EMPTY_QUEUE: PendingQueue = { held: false, items: [] };

export type PendingQueueControl = {
  queue: PendingQueue;
  /** 저장된 대기 줄을 다시 읽는다. 읽지 못하면 보이던 것을 그대로 둔다 */
  reload(): Promise<void>;
  enqueue(text: string): Promise<PendingResult<PendingQueue>>;
  /** 성공하면 취소한 글을 돌려준다. 입력창에 되돌릴 글이다. 화면이 그 줄을 모르면 빈 글이다 */
  cancel(pendingId: number): Promise<PendingResult<string>>;
  release(): Promise<PendingResult<PendingQueue>>;
};

/**
 * 한 대화의 대기 줄을 읽고 바꾼다.
 *
 * <p>대기 줄은 화면이 기억하지 않고 저장된 것을 보인다. 대화를 열 때 읽고, 부르는 쪽이 `pending` 사건을 받을 때
 * `reload` 로 다시 읽는다. 더하기와 풀기는 응답이 실어 온 대기 줄로 바꾼다.
 *
 * <p>요청마다 순번을 받고, 가장 늦게 시작한 요청의 결과만 반영한다. 먼저 나간 읽기가 늦게 도착해 방금 바꾼 줄을
 * 옛 줄로 되돌리지 않게 한다. 대화를 바꾸면 순번이 올라 앞 대화의 응답을 버린다. 실패는 문구로 바꾸지 않고
 * 그대로 돌려준다.
 */
export function usePendingQueue(
  conversationId: string | null,
): PendingQueueControl {
  const [loaded, setLoaded] = useState<{
    conversationId: string;
    queue: PendingQueue;
  } | null>(null);
  /** 마지막으로 시작한 요청의 순번이다 */
  const latestRequest = useRef(0);

  // 다른 대화의 대기 줄이 잠시라도 보이지 않게 읽은 대화와 맞을 때만 돌려준다.
  const queue =
    loaded !== null && loaded.conversationId === conversationId
      ? loaded.queue
      : EMPTY_QUEUE;

  async function reload(): Promise<void> {
    if (conversationId === null) return;
    const request = ++latestRequest.current;
    const result = await fetchPendingQueue(conversationId);
    if (request !== latestRequest.current || !result.ok) return;
    setLoaded({ conversationId, queue: result.data });
  }

  /** 응답이 대기 줄을 실어 오는 요청이다. 더하기와 풀기가 같은 방식으로 반영한다. */
  async function replaceWith(
    call: (id: string) => Promise<PendingResult<PendingQueue>>,
  ): Promise<PendingResult<PendingQueue>> {
    if (conversationId === null) {
      return { ok: false, code: "NETWORK", message: "" };
    }
    const request = ++latestRequest.current;
    const result = await call(conversationId);
    if (result.ok && request === latestRequest.current) {
      setLoaded({ conversationId, queue: result.data });
    }
    return result;
  }

  async function cancel(pendingId: number): Promise<PendingResult<string>> {
    if (conversationId === null) {
      return { ok: false, code: "NETWORK", message: "" };
    }
    const text = queue.items.find((item) => item.id === pendingId)?.text ?? "";
    const request = ++latestRequest.current;
    const result = await cancelPending(conversationId, pendingId);
    if (!result.ok) return result;
    if (request === latestRequest.current) {
      setLoaded((previous) =>
        previous === null || previous.conversationId !== conversationId
          ? previous
          : {
              conversationId,
              queue: {
                held: previous.queue.held,
                items: previous.queue.items.filter(
                  (item) => item.id !== pendingId,
                ),
              },
            },
      );
    }
    return { ok: true, data: text };
  }

  useEffect(() => {
    if (conversationId === null) return;
    const request = ++latestRequest.current;
    const controller = new AbortController();
    void fetchPendingQueue(conversationId, controller.signal).then((result) => {
      if (request !== latestRequest.current || !result.ok) return;
      setLoaded({ conversationId, queue: result.data });
    });
    return () => {
      // 대화를 바꾸거나 화면이 사라지면 나간 요청의 응답을 모두 버린다.
      latestRequest.current++;
      controller.abort();
    };
  }, [conversationId]);

  return {
    queue,
    reload,
    enqueue: (text) => replaceWith((id) => enqueuePending(id, text)),
    cancel,
    release: () => replaceWith(sendPending),
  };
}
