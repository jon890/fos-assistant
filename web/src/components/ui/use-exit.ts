"use client";

import { useCallback, useEffect, useRef, useState } from "react";

/**
 * 줄을 지우기 전에 나가는 움직임을 보인다. `leaving` 인 동안 줄에 `data-leaving` 을 주고, 끝나면 `remove` 를 부른다.
 *
 * <p>줄인 움직임 설정이면 기다리지 않고 바로 `remove` 를 부른다. 기다리는 동안 언마운트되면 타이머를 지우고
 * `remove` 를 그 자리에서 부른다. 서버에서 이미 지운 줄이 목록에 남지 않게 하려는 것이다.
 */
export function useExit(durationMs = 120): {
  leaving: boolean;
  exit(remove: () => void): void;
} {
  const [leaving, setLeaving] = useState(false);
  const pending = useRef<{ timer: number; remove(): void } | null>(null);

  const flush = useCallback(() => {
    const waiting = pending.current;
    if (!waiting) return;
    pending.current = null;
    window.clearTimeout(waiting.timer);
    waiting.remove();
  }, []);

  useEffect(() => flush, [flush]);

  const exit = useCallback(
    (remove: () => void) => {
      // 앞서 지운 줄이 아직 나가는 중이면 그 줄부터 뺀다.
      flush();
      if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
        remove();
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
