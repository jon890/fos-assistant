import { useEffect, useRef } from "react";
import { BINDING_POLL_LIMIT, BINDING_POLL_MS } from "@/lib/binding-poll";

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
