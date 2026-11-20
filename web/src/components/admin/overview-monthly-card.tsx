"use client";

import { Notice } from "@/components/ui/notice";
import { formatAmount } from "@/lib/format";
import { readOverviewMonthly } from "@/lib/admin-overview-api";
import { OverviewCard } from "./overview-card";
import { useOverviewRead } from "./use-overview-read";

/**
 * 「이번 달 사용량」 카드다. 그룹 전체의 이번 달 실행 수, 구독 경로 실행 수, 예상 추가 사용 요금을 보인다.
 *
 * <p>링크가 여는 「사용량과 비용」 은 관리자 자신의 실행만 세므로 링크 글자에 「내」 를 붙인다.
 */
export function OverviewMonthlyCard() {
  const [read, retry] = useOverviewRead(readOverviewMonthly);
  return (
    <OverviewCard
      title="이번 달 사용량"
      testId="overview-monthly"
      read={read}
      onRetry={retry}
      link={{ href: "/admin/usage", label: "내 사용량 자세히" }}
    >
      {(monthly) => (
        <>
          <p className="flex items-baseline justify-between gap-2">
            <span className="text-muted-foreground">실행</span>
            <span
              data-testid="overview-monthly-total"
              className="text-xl font-semibold tabular-nums"
            >
              {`${monthly.totalExecutions.toLocaleString("ko-KR")}건`}
            </span>
          </p>
          <p className="text-muted-foreground">
            {`${monthly.subscriptionExecutions.toLocaleString("ko-KR")}건은 구독 경로로 실행했어요`}
          </p>
          <p className="flex items-baseline justify-between gap-2">
            <span className="text-muted-foreground">예상 추가 사용 요금</span>
            <span className="tabular-nums">
              {formatAmount(monthly.actualCostMicros, monthly.currency)}
            </span>
          </p>
          {monthly.incomplete ? (
            <Notice variant="warning">
              금액을 확인하지 못한 실행이 있어 실제보다 적을 수 있어요
            </Notice>
          ) : null}
        </>
      )}
    </OverviewCard>
  );
}
