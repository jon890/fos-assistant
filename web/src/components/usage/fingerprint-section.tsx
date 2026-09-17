import { formatAmount } from "@/lib/format";
import type { BreakdownRow } from "./breakdown-table";

/** 지문은 길어서 앞자리만 보인다. 구간을 구분할 수 있으면 충분하다. */
function shortFingerprint(fingerprint: string): string {
  return fingerprint.length > 8 ? `${fingerprint.slice(0, 8)}…` : fingerprint;
}

function formatDay(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";
  return new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric" }).format(date);
}

/** 그 지문이 쓰인 구간이다. 하루 안에 끝났으면 날짜를 한 번만 적는다. */
function formatSpan(row: BreakdownRow): string {
  const first = formatDay(row.firstSeenAt);
  const last = formatDay(row.lastSeenAt);
  return first === last ? first : `${first}부터 ${last}`;
}

function perExecution(row: BreakdownRow, currency: string): string {
  if (row.estimatedCostMicros === null) return "가격 없음";
  return `${formatAmount(Math.round(row.estimatedCostMicros / row.executions), currency)}/실행`;
}

/**
 * 설정 지문이 바뀐 구간마다 실행당 평균 비용을 보인다.
 *
 * 지문이 하나뿐이면 그리지 않는다. 견줄 것이 없어 실행당 평균만 한 줄 남고,
 * 그 줄은 무엇이 달라졌는지 아무것도 말하지 못한다. 지문이 하나도 없을 때도 같다.
 */
export function FingerprintSection({ rows, currency }: { rows: BreakdownRow[]; currency: string }) {
  if (rows.length < 2) return null;

  return (
    <section aria-label="무엇이 달라졌나" className="mb-8" data-testid="fingerprint-section">
      <h2 className="mb-3 text-lg font-semibold">무엇이 달라졌나</h2>
      <ul className="grid gap-3">
        {rows.map((row) => (
          <li key={row.key} className="rounded-md border border-border p-4">
            <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
              <span className="font-medium">지문 {shortFingerprint(row.key)}</span>
              <span className="text-sm font-semibold tabular-nums">{perExecution(row, currency)}</span>
            </div>
            <p className="mt-2 text-xs text-muted">
              실행 {row.executions.toLocaleString("ko-KR")}건 · {formatSpan(row)}
            </p>
          </li>
        ))}
      </ul>
    </section>
  );
}
