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

export type LatencyStat = {
  count: number;
  p50Ms: number | null;
  p90Ms: number | null;
};

export type LatencyRow = {
  date: string;
  modelTier: "FAST" | "BALANCED" | "DEEP" | null;
  turns: number;
  firstResponse: LatencyStat;
  toSubmit: LatencyStat;
  toFirstDelta: LatencyStat;
};

export type LatencySummary = {
  days: number;
  rows: LatencyRow[];
};

/** 밀리초를 `1.8초` 처럼 초로 그린다. 값이 없으면 대시다. */
function seconds(ms: number | null): string {
  return ms === null ? "-" : `${(ms / 1000).toFixed(1)}초`;
}

/** 중앙값과 90번째 백분위를 한 줄로 그린다. 잰 turn 이 없으면 대시다. */
function stat(value: LatencyStat): string {
  return value.count === 0
    ? "-"
    : `${seconds(value.p50Ms)} / ${seconds(value.p90Ms)}`;
}

/**
 * 응답의 날짜(`2026-10-03`)를 「10월 3일」 로 그린다.
 * 시간대에 따라 하루가 밀리지 않게 날짜 조각을 직접 끊어 만든다.
 */
function dayLabel(date: string): string {
  const parts = date.split("-").map(Number);
  if (parts.length !== 3 || parts.some(Number.isNaN)) return date;
  const [year, month, day] = parts;
  return new Intl.DateTimeFormat("ko-KR", {
    month: "long",
    day: "numeric",
  }).format(new Date(year, month - 1, day));
}

function tierLabel(tier: LatencyRow["modelTier"]): string {
  return tier ?? "단계 없음";
}

function rowKey(row: LatencyRow): string {
  return `${row.date}-${row.modelTier ?? "NONE"}`;
}

/**
 * 첫 글자가 나오기까지 걸린 시간을 날짜와 모델 단계별로 보인다.
 *
 * 관리자 사용량 화면에서만 그린다. 모델 단계와 시각 구간은 내부 값이다.
 */
export function LatencySection({ summary }: { summary: LatencySummary }) {
  return (
    <section
      aria-label="첫 반응 시간"
      className="mb-8"
      data-testid="latency-section"
    >
      <h2 className="mb-1 text-lg font-semibold">첫 반응 시간</h2>
      <p className="mb-3 text-sm text-muted-foreground">
        최근 {summary.days}일 동안 보낸 질문에 첫 글자가 나오기까지 걸린
        시간이에요. 화면을 그리는 시간은 들지 않아요.
      </p>
      {summary.rows.length === 0 ? (
        <EmptyState
          title="아직 잴 질문이 없어요"
          description="질문을 보내면 날짜별로 쌓여요."
        />
      ) : (
        <>
          <Table className="hidden text-left md:table">
            <TableHeader className="text-xs">
              <TableRow className="hover:bg-transparent">
                <TableHead>날짜</TableHead>
                <TableHead>단계</TableHead>
                <TableHead className="text-right">질문 수</TableHead>
                <TableHead className="text-right">
                  첫 반응 (중앙값 / 90번째)
                </TableHead>
                <TableHead className="text-right">제출까지</TableHead>
                <TableHead className="text-right">첫 조각까지</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {summary.rows.map((row) => (
                <TableRow
                  key={rowKey(row)}
                  data-testid="latency-row"
                  data-date={row.date}
                >
                  <TableCell className="py-3 font-medium">
                    {dayLabel(row.date)}
                  </TableCell>
                  <TableCell className="py-3">
                    {tierLabel(row.modelTier)}
                  </TableCell>
                  <TableCell className="py-3 text-right tabular-nums">
                    {row.turns.toLocaleString("ko-KR")}건
                  </TableCell>
                  <TableCell className="py-3 text-right tabular-nums">
                    {stat(row.firstResponse)}
                  </TableCell>
                  <TableCell className="py-3 text-right tabular-nums">
                    {stat(row.toSubmit)}
                  </TableCell>
                  <TableCell className="py-3 text-right tabular-nums">
                    {stat(row.toFirstDelta)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <div className="grid gap-3 md:hidden">
            {summary.rows.map((row) => (
              <Card
                key={rowKey(row)}
                className="gap-0 px-4"
                data-testid="latency-row"
                data-date={row.date}
              >
                <div className="flex items-start justify-between gap-3">
                  <h3 className="text-base font-semibold">
                    {dayLabel(row.date)}
                  </h3>
                  <span className="shrink-0 text-sm text-muted-foreground">
                    {tierLabel(row.modelTier)}
                  </span>
                </div>
                <p className="mt-3 text-sm">
                  질문 {row.turns.toLocaleString("ko-KR")}건 · 첫 반응{" "}
                  {stat(row.firstResponse)}
                </p>
                <p className="mt-1 text-xs text-muted-foreground">
                  제출까지 {stat(row.toSubmit)} · 첫 조각까지{" "}
                  {stat(row.toFirstDelta)}
                </p>
              </Card>
            ))}
          </div>
        </>
      )}
    </section>
  );
}
