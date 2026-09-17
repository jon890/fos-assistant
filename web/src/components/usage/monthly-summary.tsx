import { Stat } from "@/components/ui/stat";
import { formatAmount } from "@/lib/format";

export type MonthlyCost = {
  month: string;
  currency: string;
  estimatedCostMicros: number;
  pricedExecutions: number;
  unpricedExecutions: number;
};

type Props = {
  monthly: MonthlyCost;
};

export function MonthlySummary({ monthly }: Props) {
  return (
    <dl className="mb-6 grid gap-5 rounded-lg border border-border bg-surface p-4 sm:grid-cols-2 md:grid-cols-3">
      <Stat
        label="이번 달 환산 합계"
        value={formatAmount(monthly.estimatedCostMicros, monthly.currency)}
        detail={`${monthly.month} 사용량을 API 가격으로 계산한 금액`}
      />
      <Stat
        label="실행 건수"
        value={`${(monthly.pricedExecutions + monthly.unpricedExecutions).toLocaleString("ko-KR")}건`}
      />
      {monthly.unpricedExecutions > 0 ? (
        <Stat
          label="가격을 찾지 못한 실행"
          value={`${monthly.unpricedExecutions.toLocaleString("ko-KR")}건`}
          detail="환산 합계에서 제외됨"
          className="sm:col-span-2 md:col-span-1"
        />
      ) : null}
    </dl>
  );
}
