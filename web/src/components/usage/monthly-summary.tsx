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
    <dl className="mb-6 grid gap-3 sm:grid-cols-2 md:grid-cols-3">
      <Stat
        label="예상 추가 사용 요금"
        value={
          <span className="text-primary">
            {formatAmount(monthly.actualCostMicros, monthly.currency)}
          </span>
        }
        detail={`${monthly.month}의 예상 추가 사용 요금`}
      />
      <Stat
        label="API 가격으로 계산한 금액"
        value={formatAmount(monthly.estimatedCostMicros, monthly.currency)}
        detail="같은 사용량을 API 가격으로 계산한 금액"
      />
      <Stat
        label="실행 건수"
        value={`${totalExecutions.toLocaleString("ko-KR")}건`}
        detail={`${monthly.subscriptionExecutions.toLocaleString("ko-KR")}건은 구독 경로로 실행했어요`}
      />
      {monthly.unpricedExecutions > 0 ? (
        <Stat
          label="가격을 찾지 못한 실행"
          value={`${monthly.unpricedExecutions.toLocaleString("ko-KR")}건`}
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
