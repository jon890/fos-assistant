"use client";

import { useEffect, useState } from "react";

/** 카드 하나의 읽기 상태다. 읽는 중은 `loading`, 실패는 `failed`, 읽었으면 값을 갖는다. */
export type OverviewRead<T> =
  { state: "loading" } | { state: "failed" } | { state: "ready"; value: T };

/**
 * 요약 카드 하나가 자기 값을 읽고 다시 읽는다.
 *
 * <p>카드마다 따로 부른다. 한 카드가 실패해도 다른 카드의 상태는 그대로다(`docs/features/admin-area/spec.md` 의 REQ6).
 */
export function useOverviewRead<T>(
  read: () => Promise<T | null>,
): [OverviewRead<T>, () => void] {
  const [result, setResult] = useState<OverviewRead<T>>({ state: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    void read().then((value) => {
      if (cancelled) return;
      setResult(
        value === null ? { state: "failed" } : { state: "ready", value },
      );
    });
    return () => {
      cancelled = true;
    };
  }, [read, attempt]);

  function retry() {
    setResult({ state: "loading" });
    setAttempt((value) => value + 1);
  }

  return [result, retry];
}
