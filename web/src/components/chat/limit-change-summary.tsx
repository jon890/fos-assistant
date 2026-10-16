import type { LimitChangeSummary } from "@/lib/financial-summary";

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="whitespace-pre-wrap break-all">{value}</dd>
    </div>
  );
}

/**
 * 주문 상한 변경 카드의 요약이다. 바뀌는 상한과 운영 최대치, 지금 적용하는 상한을 보인다. 승인해야 바뀌고, 승인할 때
 * 서버가 그 순간의 최대치로 다시 본다.
 */
export function LimitChangeSummaryView({
  summary,
}: {
  summary: LimitChangeSummary;
}) {
  const unit = summary.currency ? ` ${summary.currency}` : "";
  return (
    <dl
      data-testid="approval-limit-change"
      className="grid gap-2 rounded-md bg-muted px-3 py-2 text-sm sm:grid-cols-2"
    >
      <Row label="상한" value={summary.label} />
      <Row
        label="바꿀 값"
        value={`${summary.previous}${unit} → ${summary.requested}${unit}`}
      />
      <Row label="지금 상한" value={`${summary.current}${unit}`} />
      <Row label="최대치" value={`${summary.maximum}${unit}`} />
    </dl>
  );
}
