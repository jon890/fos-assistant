import { Stat } from "@/components/ui/stat";
import { formatAmount } from "@/lib/format";

/**
 * 이번 달 합계다.
 *
 * <p>금액과 건수 구분은 관리자에게만 온다. 그 밖의 사용자에게는 null 로 오고 `totalExecutions` 만 온다.
 */
export type MonthlyCost = {
  month: string;
  currency: string | null;
  estimatedCostMicros: number | null;
  pricedExecutions: number | null;
  unpricedExecutions: number | null;
  actualCostMicros: number | null;
  subscriptionExecutions: number | null;
  /** 그 달 실행 건수. 모두에게 온다 */
  totalExecutions: number;
};

type Props = {
  monthly: MonthlyCost;
  isAdmin: boolean;
};

/** 이번 달 합계다. 금액과 가격표 이야기는 관리자에게만 그리고, 그 밖의 사용자에게는 실행 건수만 그린다. */
export function MonthlySummary({ monthly, isAdmin }: Props) {
  const totalExecutions = monthly.totalExecutions;
  if (!isAdmin) {
    return (
      <dl className="mb-6 grid gap-3 sm:grid-cols-2 md:grid-cols-3">
        <Stat
          label="이번 달 실행"
          value={`${totalExecutions.toLocaleString("ko-KR")}건`}
        />
      </dl>
    );
  }
  // 여기부터는 관리자에게만 그린다. 관리자의 응답에는 아래 값이 모두 실려 온다.
  const unpricedExecutions = monthly.unpricedExecutions ?? 0;
  return (
    <dl className="mb-6 grid gap-3 sm:grid-cols-2 md:grid-cols-3">
      <Stat
        label="예상 추가 사용 요금"
        value={formatAmount(monthly.actualCostMicros ?? 0, monthly.currency)}
        detail={`${monthly.month}의 예상 추가 사용 요금`}
      />
      <Stat
        label="API 가격으로 계산한 금액"
        value={formatAmount(monthly.estimatedCostMicros ?? 0, monthly.currency)}
        detail="같은 사용량을 API 가격으로 계산한 금액"
      />
      <Stat
        label="실행 건수"
        value={`${totalExecutions.toLocaleString("ko-KR")}건`}
        detail={`${(monthly.subscriptionExecutions ?? 0).toLocaleString("ko-KR")}건은 구독 경로로 실행했어요`}
      />
      {unpricedExecutions > 0 ? (
        <Stat
          label="가격을 찾지 못한 실행"
          value={`${unpricedExecutions.toLocaleString("ko-KR")}건`}
          detail="환산 합계에서 제외됨"
          className="sm:col-span-2 md:col-span-1"
        />
      ) : null}
      <div className="sm:col-span-2 md:col-span-3">
        <p className="text-xs text-muted-foreground">
          두 금액은 공개 가격표를 이용한 계산값이에요. 실제 청구 금액과 다를 수
          있어요.
        </p>
      </div>
    </dl>
  );
}
