"use client";

import { useCallback, useSyncExternalStore } from "react";

/**
 * 화면 폭 조건이 맞는지 돌려준다.
 *
 * <p>서버에서 그린 첫 그림과 hydration 동안은 폭을 모르므로 null 이다. 부르는 쪽이 그때 그릴 모양을 정한다.
 */
export function useMediaQuery(query: string): boolean | null {
  const subscribe = useCallback((onChange: () => void) => {
    const list = window.matchMedia(query);
    list.addEventListener("change", onChange);
    return () => list.removeEventListener("change", onChange);
  }, [query]);
  return useSyncExternalStore(subscribe, () => window.matchMedia(query).matches, () => null);
}
