import { Stat } from "@/components/ui/stat";
import { formatAmount } from "@/lib/format";

export type MonthlyCost = {
  month: string;
  currency: string;
  estimatedCostMicros: number;
  pricedExecutions: number;
  unpricedExecutions: number;
  actualCostMicros: number;
  subscriptionExecutions: number;
};

type Props = {
  monthly: MonthlyCost;
};

export function MonthlySummary({ monthly }: Props) {
  const totalExecutions = monthly.pricedExecutions + monthly.unpricedExecutions;
  return (
    <dl className="mb-6 grid gap-5 rounded-md border border-border bg-surface p-4 sm:grid-cols-2 md:grid-cols-3">
      <Stat
        label="실제로 나간 돈"
        value={
          <span className="text-brand">
            {formatAmount(monthly.actualCostMicros, monthly.currency)}
          </span>
        }
        detail={`${monthly.month} 실제로 청구되는 금액`}
      />
      <Stat
        label="API 로 돌렸다면"
        value={formatAmount(monthly.estimatedCostMicros, monthly.currency)}
        detail="같은 사용량을 API 가격으로 계산한 금액"
      />
      <Stat
        label="실행 건수"
        value={`${totalExecutions.toLocaleString("ko-KR")}건`}
        detail={`${monthly.subscriptionExecutions.toLocaleString("ko-KR")}건이 구독 경로다`}
      />
      {monthly.unpricedExecutions > 0 ? (
        <Stat
          label="가격을 찾지 못한 실행"
          value={`${monthly.unpricedExecutions.toLocaleString("ko-KR")}건`}
          detail="환산 합계에서 제외됨"
          className="sm:col-span-2 md:col-span-1"
        />
      ) : null}
      <p className="text-xs text-muted sm:col-span-2 md:col-span-3">
        두 금액 모두 공개 가격표로 계산한 것이고 청구서를 읽은 것이 아니다.
      </p>
    </dl>
  );
}
