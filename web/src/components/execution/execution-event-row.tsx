import { formatDuration } from "@/lib/format";

/** 여러 사건을 합쳐 그리려고 만든 한 줄이다. */
export type MergedEventRow =
  | {
      kind: "tool";
      key: number;
      toolName: string | null;
      durationMs: number | null;
      detail: string | null;
      finished: boolean;
    }
  | { kind: "subagent"; key: number; subagentName: string | null; detail: string | null }
  | { kind: "error"; key: number; detail: string | null };

export function ExecutionEventRow({ row }: { row: MergedEventRow }) {
  if (row.kind === "error") {
    return (
      <li className="truncate text-sm text-foreground" data-testid="execution-event-row">
        실행 실패{row.detail ? `: ${row.detail}` : ""}
      </li>
    );
  }
  if (row.kind === "subagent") {
    return (
      <li className="truncate text-sm text-muted" data-testid="execution-event-row">
        하위 에이전트: {row.subagentName ?? "이름 없음"}
        {row.detail ? ` · ${row.detail}` : ""}
      </li>
    );
  }
  if (!row.finished) {
    return (
      <li className="truncate text-sm text-muted" data-testid="execution-event-row">
        도구: {row.toolName ?? "이름 없음"} · 끝나지 않음
      </li>
    );
  }
  return (
    <li className="truncate text-sm text-muted" data-testid="execution-event-row">
      도구: {row.toolName ?? "이름 없음"} · {formatDuration(row.durationMs ?? 0)}
      {row.detail ? ` · ${row.detail}` : ""}
    </li>
  );
}
