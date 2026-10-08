"use client";

import { useRef, useState } from "react";
import { describeFailure } from "@/components/error-message";
import { ExecutionList } from "@/components/usage/execution-list";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import {
  fetchUsageExecutionPage,
  mergeUsageExecutionPage,
  type UsageExecutionPage,
} from "@/lib/usage-paging";

/** 초기 목록 아래에 이전 실행 기록을 이어 붙인다. */
export function PagedExecutionList({
  initialPage,
  isAdmin,
}: {
  initialPage: UsageExecutionPage;
  isAdmin: boolean;
}) {
  const [page, setPage] = useState(initialPage);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const loadingRef = useRef(false);

  async function loadMore() {
    if (loadingRef.current || page.nextCursor === null) return;
    loadingRef.current = true;
    setLoading(true);
    setError(null);
    try {
      const response = await fetchUsageExecutionPage(page.nextCursor);
      if (!response.ok) {
        setError(await describeFailure(response));
        return;
      }
      const next = (await response.json()) as UsageExecutionPage;
      setPage((current) => mergeUsageExecutionPage(current, next));
    } catch {
      setError("실행 기록을 더 읽지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    } finally {
      loadingRef.current = false;
      setLoading(false);
    }
  }

  return (
    <div className="space-y-3">
      <ExecutionList executions={page.items} isAdmin={isAdmin} />
      {error ? <Notice variant="error" role="alert">{error}</Notice> : null}
      {page.nextCursor !== null ? (
        <Button loading={loading} loadingText="불러오는 중…" onClick={loadMore}>
          더 보기
        </Button>
      ) : null}
    </div>
  );
}
