import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatAmount, formatCost, formatTokens } from "@/lib/format";

export type BreakdownRow = {
  key: string;
  label: string;
  detail: string | null;
  executions: number;
  subagents: number;
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

/** 예상 추가 사용 요금이 비어 있는 묶음은 구독 경로만 있었다는 뜻이라 금액을 쓰지 않는다. */
export function actualLabel(row: BreakdownRow, currency: string): string {
  return row.actualCostMicros === null
    ? "구독"
    : formatAmount(row.actualCostMicros, currency);
}

export function contextLabel(row: BreakdownRow): string {
  return row.avgContextChars === null
    ? "-"
    : `${row.avgContextChars.toLocaleString("ko-KR")}자`;
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
  return new Intl.DateTimeFormat("ko-KR", {
    month: "long",
    day: "numeric",
  }).format(new Date(year, month - 1, day));
}

export function BreakdownTable({
  rows,
  currency,
  axis,
}: {
  rows: BreakdownRow[];
  currency: string;
  axis: string;
}) {
  if (rows.length === 0) {
    return (
      <EmptyState
        title="기록이 없어요"
        description="그 달에는 완료된 실행이 없어요."
      />
    );
  }

  return (
    <>
      <Table
        className="hidden text-left md:table"
        data-testid="breakdown-table"
      >
        <TableHeader className="text-xs">
          <TableRow className="hover:bg-transparent">
            <TableHead>묶음</TableHead>
            <TableHead className="text-right">실행</TableHead>
            <TableHead className="text-right">API 환산 비용</TableHead>
            <TableHead className="text-right">예상 추가 사용 요금</TableHead>
            <TableHead className="text-right">입력</TableHead>
            <TableHead className="text-right">출력</TableHead>
            <TableHead className="text-right">문맥 평균</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={row.key} className="align-top">
              <TableCell className="max-w-48 py-3">
                <span className="block truncate font-medium">
                  {rowLabel(row, axis)}
                </span>
                {row.detail ? (
                  <span className="block truncate text-xs text-muted-foreground">
                    {row.detail}
                  </span>
                ) : null}
              </TableCell>
              <TableCell className="py-3 text-right tabular-nums">
                {row.executions.toLocaleString("ko-KR")}건
                {row.subagents > 0 ? (
                  <span className="block text-xs text-muted-foreground">
                    도우미 {row.subagents.toLocaleString("ko-KR")}건
                  </span>
                ) : null}
              </TableCell>
              <TableCell className="py-3 text-right tabular-nums">
                {formatCost(row.estimatedCostMicros, currency)}
              </TableCell>
              <TableCell className="py-3 text-right tabular-nums">
                {actualLabel(row, currency)}
              </TableCell>
              <TableCell className="py-3 text-right tabular-nums">
                {formatTokens(row.inputTokens)}
              </TableCell>
              <TableCell className="py-3 text-right tabular-nums">
                {formatTokens(row.outputTokens)}
              </TableCell>
              <TableCell className="py-3 text-right tabular-nums">
                {contextLabel(row)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <div className="grid gap-3 md:hidden" data-testid="breakdown-cards">
        {rows.map((row) => (
          <Card key={row.key} className="gap-0 px-4">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <h3 className="truncate text-base font-semibold">
                  {rowLabel(row, axis)}
                </h3>
                {row.detail ? (
                  <p className="truncate text-xs text-muted-foreground">
                    {row.detail}
                  </p>
                ) : null}
              </div>
              <span className="shrink-0 text-sm font-semibold tabular-nums">
                {formatCost(row.estimatedCostMicros, currency)}
              </span>
            </div>
            <p className="mt-3 text-sm">
              실행 {row.executions.toLocaleString("ko-KR")}건 · 문맥 평균{" "}
              {contextLabel(row)}
            </p>
            {row.subagents > 0 ? (
              <p className="mt-1 text-xs text-muted-foreground">
                도우미 {row.subagents.toLocaleString("ko-KR")}건
              </p>
            ) : null}
            <p className="mt-1 text-xs text-muted-foreground">
              예상 추가 사용 요금 {actualLabel(row, currency)} · 입력{" "}
              {formatTokens(row.inputTokens)} → 출력{" "}
              {formatTokens(row.outputTokens)}
            </p>
          </Card>
        ))}
      </div>
    </>
  );
}
