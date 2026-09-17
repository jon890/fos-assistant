import { EmptyState } from "@/components/ui/empty-state";
import { formatAmount, formatCost, formatTokens } from "@/lib/format";

export type BreakdownRow = {
  key: string;
  label: string;
  detail: string | null;
  executions: number;
  estimatedCostMicros: number | null;
  actualCostMicros: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  avgContextChars: number | null;
  firstSeenAt: string;
  lastSeenAt: string;
};

export type Breakdown = {
  axis: string;
  month: string;
  currency: string;
  rows: BreakdownRow[];
};

/** 실제 청구액이 비어 있는 묶음은 구독 경로만 있었다는 뜻이라 금액을 쓰지 않는다. */
export function actualLabel(row: BreakdownRow, currency: string): string {
  return row.actualCostMicros === null ? "구독" : formatAmount(row.actualCostMicros, currency);
}

export function contextLabel(row: BreakdownRow): string {
  return row.avgContextChars === null ? "-" : `${row.avgContextChars.toLocaleString("ko-KR")}자`;
}

/**
 * 묶음 이름이다. 날짜 축만 화면에서 다시 그린다.
 *
 * Control Plane 이 주는 날짜는 `2026-09-15` 라 다른 곳이 쓰는 「9월 15일」 과 형식이 다르다.
 * 응답의 `key` 는 줄을 구분하는 값이라 그대로 두고 보이는 글자만 바꾼다.
 * 시간대에 따라 하루가 밀리지 않게 날짜 조각을 직접 끊어 만든다.
 */
export function rowLabel(row: BreakdownRow, axis: string): string {
  if (axis !== "day") return row.label;
  const parts = row.label.split("-").map(Number);
  if (parts.length !== 3 || parts.some(Number.isNaN)) return row.label;
  const [year, month, day] = parts;
  return new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric" })
    .format(new Date(year, month - 1, day));
}

export function BreakdownTable(
  { rows, currency, axis }: { rows: BreakdownRow[]; currency: string; axis: string },
) {
  if (rows.length === 0) {
    return <EmptyState title="기록이 없다" description="그 달에는 끝난 실행이 없다." />;
  }

  return (
    <>
      <table className="hidden w-full text-left text-sm md:table" data-testid="breakdown-table">
        <thead className="text-xs text-muted">
          <tr>
            <th className="pb-3 pr-4 font-medium">묶음</th>
            <th className="pb-3 pr-4 text-right font-medium">실행</th>
            <th className="pb-3 pr-4 text-right font-medium">API 환산 비용</th>
            <th className="pb-3 pr-4 text-right font-medium">실제 청구액</th>
            <th className="pb-3 pr-4 text-right font-medium">입력</th>
            <th className="pb-3 pr-4 text-right font-medium">출력</th>
            <th className="pb-3 text-right font-medium">문맥 평균</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.key} className="border-t border-border align-top">
              <td className="max-w-48 py-3 pr-4">
                <span className="block truncate font-medium">{rowLabel(row, axis)}</span>
                {row.detail ? <span className="block truncate text-xs text-muted">{row.detail}</span> : null}
              </td>
              <td className="py-3 pr-4 text-right tabular-nums">{row.executions.toLocaleString("ko-KR")}건</td>
              <td className="py-3 pr-4 text-right whitespace-nowrap tabular-nums">
                {formatCost(row.estimatedCostMicros, currency)}
              </td>
              <td className="py-3 pr-4 text-right whitespace-nowrap tabular-nums">{actualLabel(row, currency)}</td>
              <td className="py-3 pr-4 text-right tabular-nums">{formatTokens(row.inputTokens)}</td>
              <td className="py-3 pr-4 text-right tabular-nums">{formatTokens(row.outputTokens)}</td>
              <td className="py-3 text-right whitespace-nowrap tabular-nums">{contextLabel(row)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="grid gap-3 md:hidden" data-testid="breakdown-cards">
        {rows.map((row) => (
          <article key={row.key} className="rounded-md border border-border p-4">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <h3 className="truncate font-semibold">{rowLabel(row, axis)}</h3>
                {row.detail ? <p className="truncate text-xs text-muted">{row.detail}</p> : null}
              </div>
              <span className="shrink-0 text-sm font-semibold tabular-nums">
                {formatCost(row.estimatedCostMicros, currency)}
              </span>
            </div>
            <p className="mt-3 text-sm">
              실행 {row.executions.toLocaleString("ko-KR")}건 · 문맥 평균 {contextLabel(row)}
            </p>
            <p className="mt-1 text-xs text-muted">
              실제 청구액 {actualLabel(row, currency)} · 입력 {formatTokens(row.inputTokens)} → 출력{" "}
              {formatTokens(row.outputTokens)}
            </p>
          </article>
        ))}
      </div>
    </>
  );
}
