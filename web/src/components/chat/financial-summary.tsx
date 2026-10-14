import type { FinancialSummary } from "@/lib/connector-action";

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
    </div>
  );
}
