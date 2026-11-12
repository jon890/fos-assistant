import { useEffect, useRef, type Dispatch, type SetStateAction } from "react";
import {
  BINDING_POLL_LIMIT,
  BINDING_POLL_MS,
  isAwaitingApply,
} from "@/lib/binding-poll";
import { readConnection } from "@/lib/connection";
import type { Loaded } from "@/lib/connection-load";

/**
 * `waiting` 인 동안 일정 간격으로 `refresh` 를 불러 서버가 바꾼 바인딩 상태를 읽는다.
 *
 * <p>`waiting` 이 거짓이 되면 멈추고, 끝나지 않아도 `BINDING_POLL_LIMIT` 번에서 멈춘다. `refresh` 는 매 그림마다 새로
 * 만들어져도 되도록 마지막 것을 쥐고 부르며, 요청이 돌 때는 다음 읽기를 예약하지 않아 겹치지 않는다.
 */
export function useBindingPoll(waiting: boolean, refresh: () => Promise<void>) {
  const latest = useRef(refresh);
  useEffect(() => {
    latest.current = refresh;
  });

  useEffect(() => {
    if (!waiting) return;
    let stopped = false;
    let attempts = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    function schedule() {
      timer = setTimeout(() => void tick(), BINDING_POLL_MS);
    }
    async function tick() {
      attempts += 1;
      await latest.current();
      if (!stopped && attempts < BINDING_POLL_LIMIT) schedule();
    }
    schedule();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [waiting]);
}

/**
 * 연결 하나의 화면이 읽은 상태에 기다리는 바인딩이 있으면 그 연결을 다시 읽어 바꾼다.
 *
 * <p>서버는 붙인 바인딩을 `PENDING` 으로 두었다가 반영이 끝나면 스스로 `READY` 로 바꾼다. 확인이나 해제가 도는 중(`busy`)에는
 * 그 결과와 엇갈리지 않게 읽지 않는다.
 */
export function useConnectionPoll(
  id: string,
  validId: boolean,
  loaded: Loaded,
  busy: boolean,
  setLoaded: Dispatch<SetStateAction<Loaded>>,
) {
  const waiting =
    loaded.kind === "ready" && loaded.connection.bindings.some(isAwaitingApply);
  useBindingPoll(waiting, async () => {
    if (!validId || busy) return;
    const fresh = await readConnection(id);
    if (!fresh.ok) return;
    setLoaded((current) =>
      current.kind === "ready"
        ? { ...current, connection: fresh.data }
        : current,
    );
  });
}
