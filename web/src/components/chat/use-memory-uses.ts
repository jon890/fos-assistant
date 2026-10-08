"use client";

import { useCallback, useEffect, useState } from "react";
import { readMemoryUses, type MemoryUse } from "@/lib/memory-use-api";

type Loaded = { conversationId: string; uses: MemoryUse[] };

/**
 * 그 대화의 답마다 참고한 기억을 읽는다.
 *
 * <p>대화나 `refreshKey` 가 바뀌면 다시 읽는다. 읽지 못해도 대화를 막지 않게 조용히 앞의 목록을 둔다.
 * 앞선 대화의 목록을 다른 대화에 돌려주지 않는다. 기억 기록과 한 훅으로 합치지 않는다.
 * 합치면 한쪽을 읽지 못했을 때 다른 쪽 목록까지 함께 사라진다.
 */
export function useMemoryUses(
  conversationId: string | null,
  refreshKey: string,
) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reloads, setReloads] = useState(0);

  useEffect(() => {
    if (conversationId === null) return;
    let stale = false;
    void readMemoryUses(conversationId).then((result) => {
      if (stale || !result.ok) return;
      setLoaded({ conversationId, uses: result.data });
    });
    return () => {
      stale = true;
    };
  }, [conversationId, refreshKey, reloads]);

  /** 기억 기록을 되돌리거나 고치거나 받아들인 뒤에 부른다. 서버 목록을 다시 읽기만 한다. */
  const reload = useCallback(() => setReloads((count) => count + 1), []);

  return {
    uses:
      loaded !== null && loaded.conversationId === conversationId
        ? loaded.uses
        : [],
    reload,
  };
}
