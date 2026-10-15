import { Notice } from "@/components/ui/notice";
import { LimitChangeSummaryView } from "@/components/chat/limit-change-summary";
import type { ConnectorAction } from "@/lib/connector-action";
import {
  compareDecimal,
  decimalExceeds,
  multiplyDecimal,
  parseDecimal,
  type Decimal,
} from "@/lib/decimal";
import type {
  FinancialLimitStatus,
  FinancialSummary,
} from "@/lib/financial-summary";

const OPERATION_LABELS = { CREATE: "주문", CANCEL: "주문 취소" } as const;
const SIDE_LABELS = { BUY: "매수", SELL: "매도" } as const;
const ORDER_TYPE_LABELS = { LIMIT: "지정가", MARKET: "시장가" } as const;

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="whitespace-pre-wrap break-all">{value}</dd>
    </div>
  );
}

/**
 * 금융 줄과 주문 상한 변경 줄은 모델 인자가 아니라 Control Plane 이 만든 요약을 보고 승인한다. 요약이 없거나 열지
 * 못했으면 인자만 보고 승인하지 못하게 한다. `closed` 는 기다리는 그런 줄이 요약 없이 남은 경우다.
 */
export function financialView(action: ConnectorAction) {
  const change = action.limitChange ?? null;
  const line =
    action.financial != null || action.risk === "FINANCIAL" || change != null;
  const summary = action.financial?.available ? action.financial : null;
  const limitChange = change?.available ? change : null;
  return {
    line,
    summary,
    limitChange,
    closed:
      action.status === "PENDING" &&
      line &&
      summary === null &&
      limitChange === null,
  };
}

/** 금융 줄과 상한 변경 줄의 본문이다. 요약을 열지 못한 줄은 거절만 할 수 있다고 알린다. */
export function FinancialLineView({ action }: { action: ConnectorAction }) {
  const { summary, limitChange, closed } = financialView(action);
  return (
    <>
      {summary ? <FinancialSummaryView summary={summary} /> : null}
      {limitChange ? <LimitChangeSummaryView summary={limitChange} /> : null}
      {closed ? (
        <Notice
          variant="warning"
          role="status"
          data-testid="approval-financial-unavailable"
        >
          {action.limitChange
            ? "상한 변경 내용을 열지 못해 승인할 수 없어요. 거절만 할 수 있어요."
            : "주문 내용을 열지 못해 승인할 수 없어요. 거절만 할 수 있어요."}
        </Notice>
      ) : null}
    </>
  );
}

/** 지정가는 수량과 가격의 곱, 금액 주문은 그 금액이다. 정할 수 없으면 null 이다. 부동소수점 없이 계산한다. */
function orderValue(summary: FinancialSummary): Decimal | null {
  if (summary.orderAmount) return parseDecimal(summary.orderAmount);
  const quantity = summary.quantity ? parseDecimal(summary.quantity) : null;
  const price = summary.price ? parseDecimal(summary.price) : null;
  return quantity && price ? multiplyDecimal(quantity, price) : null;
}

/** 이 주문이 넘는 상한의 안내다. 넘지 않으면 null 이다. 판정은 승인할 때 서버가 다시 한다. */
function exceededLimit(
  summary: FinancialSummary,
  limits: FinancialLimitStatus,
): string | null {
  if (
    summary.quantity &&
    decimalExceeds(summary.quantity, limits.quantityLimit)
  )
    return "이 주문은 한 건 수량 상한을 넘어요.";
  const value = orderValue(summary);
  const over = (limit: string) => {
    const parsed = parseDecimal(limit);
    return (
      value !== null && parsed !== null && compareDecimal(value, parsed) > 0
    );
  };
  if (over(limits.orderLimit)) return "이 주문은 한 건 금액 상한을 넘어요.";
  if (over(limits.remainingToday)) return "이 주문은 오늘 남은 한도를 넘어요.";
  return null;
}

/** 주문 카드의 상한과 오늘 남은 한도다. 넘는 상한이 있으면 경고로 알린다. */
function LimitStatusView({ summary }: { summary: FinancialSummary }) {
  const limits = summary.limits;
  if (!limits) return null;
  const unit = ` ${limits.currency}`;
  const exceeded = exceededLimit(summary, limits);
  return (
    <div
      data-testid="approval-financial-limits"
      className="flex flex-col gap-2"
    >
      <dl className="grid gap-2 rounded-md bg-muted px-3 py-2 text-sm sm:grid-cols-2">
        <Row label="오늘 남은 한도" value={`${limits.remainingToday}${unit}`} />
        <Row label="하루 상한" value={`${limits.dailyLimit}${unit}`} />
        <Row label="한 건 상한" value={`${limits.orderLimit}${unit}`} />
        <Row label="오늘 쓴 금액" value={`${limits.spentToday}${unit}`} />
      </dl>
      {exceeded ? (
        <Notice variant="warning" role="status">
          {exceeded} 승인하면 거절될 거예요.
        </Notice>
      ) : null}
    </div>
  );
}

/** 사람이 다 읽지 못한 인자나 열지 못한 금융 요약으로는 승인을 받지 않는다. 서버도 이런 줄의 승인을 실행하지 않고 끝낸다. */
export function approvalBlocked(action: ConnectorAction) {
  return (
    (action.status === "PENDING" && action.hiddenArgs) ||
    financialView(action).closed
  );
}

/**
 * 금융 승인 줄의 저장 요약이다. 모델이 낸 인자가 아니라 Control Plane 이 저장해 검증한 내용과 실제로 전송할 계좌
 * 순번을 글자 그대로 보인다. 요약을 열지 못한 줄은 부른 쪽이 이 블록을 그리지 않는다.
 */
export function FinancialSummaryView({
  summary,
}: {
  summary: FinancialSummary;
}) {
  const { currency, market } = summary;
  return (
    <div data-testid="approval-financial" className="flex flex-col gap-2">
      <dl className="grid gap-2 rounded-md bg-muted px-3 py-2 text-sm sm:grid-cols-2">
        <Row label="계좌 순번" value={summary.accountSeq ?? ""} />
        <Row
          label="동작"
          value={summary.operation ? OPERATION_LABELS[summary.operation] : ""}
        />
        {summary.operation === "CANCEL" && summary.orderId ? (
          <Row label="주문 번호" value={summary.orderId} />
        ) : null}
        <Row
          label="종목"
          value={`${summary.symbol ?? ""}${market ? ` (${market})` : ""}`}
        />
        <Row
          label="방향"
          value={summary.side ? SIDE_LABELS[summary.side] : ""}
        />
        <Row
          label="주문 유형"
          value={summary.orderType ? ORDER_TYPE_LABELS[summary.orderType] : ""}
        />
        {summary.quantity ? (
          <Row label="수량" value={`${summary.quantity}주`} />
        ) : null}
        {summary.orderAmount ? (
          <Row label="금액" value={`${summary.orderAmount} ${currency}`} />
        ) : null}
        {summary.price ? (
          <Row label="가격" value={`${summary.price} ${currency}`} />
        ) : null}
      </dl>
      {summary.normalization.length > 0 ? (
        <div data-testid="approval-financial-normalization" className="text-sm">
          <p className="text-xs text-muted-foreground">
            입력을 이렇게 바꿨어요
          </p>
          <ul className="mt-1 flex flex-col gap-1">
            {summary.normalization.map((change, index) => (
              <li key={`${change.field}:${index}`} className="break-all">
                {change.field}: {change.before ?? "없음"} → {change.after}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      <LimitStatusView summary={summary} />
    </div>
  );
}
