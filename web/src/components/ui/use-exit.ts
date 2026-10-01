"use client";

import { useCallback, useEffect, useRef, useState } from "react";

/**
 * 줄을 지우기 전에 나가는 움직임을 보인다. `leaving` 인 동안 줄에 `data-leaving` 을 주고, 끝나면 `remove` 를 부른다.
 *
 * <p>줄인 움직임 설정이면 기다리지 않고 바로 `remove` 를 부른다. 기다리는 동안 언마운트되면 타이머를 지우고
 * `remove` 를 그 자리에서 부른다. 서버에서 이미 지운 줄이 목록에 남지 않게 하려는 것이다.
 *
 * <p>`remove` 가 끝나면 `leaving` 을 내린다. `remove` 가 목록을 다시 읽다 실패해 줄이 남아도 투명한 채로
 * 두지 않으려는 것이다. `remove` 가 Promise 를 돌려주면 그것이 끝난 뒤에 내려, 다시 읽는 동안 줄이 도로 나타나지 않는다.
 */
export function useExit(durationMs = 120): {
  leaving: boolean;
  exit(remove: () => void | Promise<void>): void;
} {
  const [leaving, setLeaving] = useState(false);
  const pending = useRef<{
    timer: number;
    remove(): void | Promise<void>;
  } | null>(null);
  const mounted = useRef(false);

  const flush = useCallback(() => {
    const waiting = pending.current;
    if (!waiting) return;
    pending.current = null;
    window.clearTimeout(waiting.timer);
    const settle = () => {
      // 언마운트된 뒤이거나 그 사이 다른 줄이 나가기 시작했으면 건드리지 않는다.
      if (mounted.current && pending.current === null) setLeaving(false);
    };
    void Promise.resolve(waiting.remove()).then(settle, settle);
  }, []);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      flush();
    };
  }, [flush]);

  const exit = useCallback(
    (remove: () => void | Promise<void>) => {
      // 앞서 지운 줄이 아직 나가는 중이면 그 줄부터 뺀다.
      flush();
      if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
        void remove();
        return;
      }
      setLeaving(true);
      pending.current = {
        timer: window.setTimeout(flush, durationMs),
        remove,
      };
    },
    [durationMs, flush],
  );

  return { leaving, exit };
}
