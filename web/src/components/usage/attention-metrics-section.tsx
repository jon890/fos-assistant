import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ratioText, reasonText } from "@/lib/attention";
import { formatDuration } from "@/lib/format";

export type AttentionMetricRow = {
  trigger: string;
  shown: number;
  hidden: number;
  snoozed: number;
  acted: number;
  nowShown: number;
  nowHiddenWithoutAction: number;
  staleShown: number;
  medianSecondsToFirstAction: number | null;
};

export type AttentionMetrics = {
  days: number;
  rows: AttentionMetricRow[];
};

/** 종류 칸의 글이다. 이유 문구 표에서 `signals` 가 없는 줄의 문구를 쓴다. */
function triggerLabel(trigger: string): string {
  const text = reasonText({
    trigger,
    signals: [],
    confidence: "",
    sources: [],
  });
  return text === "" ? trigger : text;
}

function firstAction(seconds: number | null): string {
  return seconds === null ? "-" : formatDuration(seconds * 1000);
}

/**
 * 지금 화면에 보인 항목을 종류별로 숨김, 미루기, 행동 비율로 보인다.
 *
 * 관리자 사용량 화면에서만 그린다. 일반 사용량 화면에는 역할로 갈리는 표시를 두지 않는다.
 */
export function AttentionMetricsSection({
  metrics,
}: {
  metrics: AttentionMetrics;
}) {
  return (
    <section
      aria-label="먼저 알리기"
      className="mb-8"
      data-testid="attention-metrics-section"
    >
      <h2 className="mb-1 text-lg font-semibold">먼저 알리기</h2>
      <p className="mb-3 text-sm text-muted-foreground">
        최근 {metrics.days}일 동안 지금 화면에 보인 항목을 종류별로 모았어요.
      </p>
      {metrics.rows.length === 0 ? (
        <p className="text-sm">아직 지금 화면에 보인 항목이 없어요.</p>
      ) : (
        <Table className="text-left">
          <TableHeader className="text-xs">
            <TableRow className="hover:bg-transparent">
              <TableHead>종류</TableHead>
              <TableHead className="text-right">보인 수</TableHead>
              <TableHead className="text-right">숨김 비율</TableHead>
              <TableHead className="text-right">미루기 비율</TableHead>
              <TableHead className="text-right">행동 비율</TableHead>
              <TableHead className="text-right">지금 표시의 헛보임</TableHead>
              <TableHead className="text-right">첫 행동까지</TableHead>
              <TableHead className="text-right">오래된 항목 비율</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {metrics.rows.map((row) => (
              <TableRow key={row.trigger} data-testid="attention-metric-row">
                <TableCell className="py-3 font-medium">
                  {triggerLabel(row.trigger)}
                </TableCell>
                <TableCell className="py-3 text-right tabular-nums">
                  {row.shown.toLocaleString("ko-KR")}
                </TableCell>
                <TableCell className="py-3 text-right tabular-nums">
                  {ratioText(row.hidden, row.shown)}
                </TableCell>
                <TableCell className="py-3 text-right tabular-nums">
                  {ratioText(row.snoozed, row.shown)}
                </TableCell>
                <TableCell className="py-3 text-right tabular-nums">
                  {ratioText(row.acted, row.shown)}
                </TableCell>
                <TableCell className="py-3 text-right tabular-nums">
                  {ratioText(row.nowHiddenWithoutAction, row.nowShown)}
                </TableCell>
                <TableCell className="py-3 text-right tabular-nums">
                  {firstAction(row.medianSecondsToFirstAction)}
                </TableCell>
                <TableCell className="py-3 text-right tabular-nums">
                  {ratioText(row.staleShown, row.shown)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </section>
  );
}
