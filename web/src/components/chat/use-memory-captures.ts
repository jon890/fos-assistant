"use client";

import { useCallback, useEffect, useState } from "react";
import {
  readMemoryCaptures,
  type MemoryCapture,
} from "@/lib/memory-capture-api";

type Loaded = { conversationId: string; captures: MemoryCapture[] };

/**
 * 그 대화의 기억 기록을 읽는다.
 *
 * <p>대화나 `refreshKey` 가 바뀌면 다시 읽는다. 읽지 못해도 대화를 막지 않게 조용히 앞의 목록을 둔다.
 * 앞선 대화의 기록을 다른 대화에 돌려주지 않는다.
 */
export function useMemoryCaptures(
  conversationId: string | null,
  refreshKey: string,
) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reloads, setReloads] = useState(0);

  useEffect(() => {
    if (conversationId === null) return;
    let stale = false;
    void readMemoryCaptures(conversationId).then((result) => {
      if (stale || !result.ok) return;
      setLoaded({ conversationId, captures: result.data });
    });
    return () => {
      stale = true;
    };
  }, [conversationId, refreshKey, reloads]);

  /** 처리한 뒤에 부른다. 지울 줄은 먼저 빼고 서버 목록을 다시 읽는다. */
  const changed = useCallback((removedId?: number) => {
    if (removedId !== undefined)
      setLoaded(
        (current) =>
          current && {
            ...current,
            captures: current.captures.filter(
              (capture) => capture.id !== removedId,
            ),
          },
      );
    setReloads((count) => count + 1);
  }, []);

  return {
    captures:
      loaded !== null && loaded.conversationId === conversationId
        ? loaded.captures
        : [],
    changed,
  };
}
