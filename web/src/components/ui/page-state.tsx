"use client";

import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * 목록을 불러오지 못했을 때의 안내다. 한 줄과 「다시 읽기」 다(docs/features/app-shell/design.md 의 「빈 목록, 실패, 읽는 중」).
 *
 * <p>`onRetry` 가 없으면 서버가 읽는 화면으로 보고 문서를 새로 연다. `router.refresh()` 는 빌드한 서버에서
 * 응답이 화면에 반영되지 않은 일이 있어 쓰지 않는다(`agent-detail-body.tsx` 의 설명).
 */
export function LoadFailure({
  message,
  onRetry,
  ...props
}: {
  message: string;
  onRetry?: () => void;
  "data-testid"?: string;
}) {
  return (
    <Notice variant="error" role="alert" data-slot="load-failure" {...props}>
      <p>{message}</p>
      <Button
        variant="outline"
        size="sm"
        className="mt-2"
        onClick={() => (onRetry ? onRetry() : window.location.reload())}
      >
        다시 읽기
      </Button>
    </Notice>
  );
}

/** 목록을 읽는 동안 본문 자리에 두는 뼈대다. 머리는 화면이 그대로 그린다. */
export function ListSkeleton({
  rows = 3,
  ...props
}: {
  rows?: number;
  "data-testid"?: string;
}) {
  return (
    <div
      role="status"
      aria-label="불러오는 중"
      data-slot="list-skeleton"
      className="flex flex-col gap-2"
      {...props}
    >
      {Array.from({ length: rows }, (_, index) => (
        <Skeleton key={index} className="h-16" />
      ))}
    </div>
  );
}
