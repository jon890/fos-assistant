"use client";

import { useEffect, useState } from "react";
import {
  readContentDraftJobs,
  type ContentDraftJob,
} from "@/lib/content-draft-snapshot";

/**
 * 원고 job 목록이다. 일반 승인 줄 목록은 최근 20개뿐이라 그 밖으로 밀린 원고 결과를 여기서 다시 찾는다.
 * `paged` 면 「이전 저장 더 보기」 로 다음 페이지를 읽은 뒤라 첫 페이지를 다시 읽어도 cursor 를 되돌리지 않는다.
 */
type Jobs = {
  conversationId: string;
  first: ContentDraftJob[];
  older: ContentDraftJob[];
  next: string | null;
  paged: boolean;
};

/**
 * 그 대화의 원고 job 을 첫 페이지부터 읽는다. 「이전 저장 더 보기」 가 `loadMore` 로 다음 페이지를 붙인다.
 * 원격 상태를 조회하지 않는 GET 이라 승인 사건이 거듭 와도 저장을 다시 보내지 않는다.
 */
export function useContentDraftJobs(
  conversationId: string | null,
  reloadKey: number,
) {
  const [jobs, setJobs] = useState<Jobs | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  useEffect(() => {
    if (conversationId === null) return;
    let stale = false;
    void readContentDraftJobs(conversationId, null).then((result) => {
      if (stale || !result.ok) return;
      setJobs((current) => {
        const kept =
          current?.conversationId === conversationId && current.paged;
        return {
          conversationId,
          first: result.data.items,
          older: kept ? current.older : [],
          next: kept ? current.next : result.data.nextBeforeSnapshotId,
          paged: kept,
        };
      });
    });
    return () => {
      stale = true;
    };
  }, [conversationId, reloadKey]);

  const current = jobs?.conversationId === conversationId ? jobs : null;

  async function loadMore() {
    const next = current?.next;
    if (!next || conversationId === null || loadingMore) return;
    setLoadingMore(true);
    const result = await readContentDraftJobs(conversationId, next);
    setLoadingMore(false);
    if (!result.ok) return;
    const page = result.data;
    setJobs((current) =>
      current?.conversationId === conversationId
        ? {
            ...current,
            older: [...current.older, ...page.items],
            next: page.nextBeforeSnapshotId,
            paged: true,
          }
        : current,
    );
  }

  return {
    items: current ? [...current.first, ...current.older] : [],
    next: current?.next ?? null,
    loadingMore,
    loadMore,
  };
}
