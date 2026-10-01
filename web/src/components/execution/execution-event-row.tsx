import { formatDuration, subagentLabel } from "@/lib/format";
import { toolLabel } from "@/lib/tool-label";

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
  | {
      kind: "subagent";
      key: number;
      subagentName: string | null;
      detail: string | null;
      usageStatus: "WAITING" | "RECORDED" | "UNCONFIRMED" | null;
    }
  | { kind: "error"; key: number; detail: string | null }
  | { kind: "cancelled"; key: number };

export function ExecutionEventRow({ row }: { row: MergedEventRow }) {
  if (row.kind === "error") {
    return (
      <li
        className="truncate text-sm text-foreground"
        data-testid="execution-event-row"
      >
        실행 실패{row.detail ? `: ${row.detail}` : ""}
      </li>
    );
  }
  if (row.kind === "cancelled") {
    return (
      <li
        className="truncate text-sm text-muted-foreground"
        data-testid="execution-event-row"
      >
        중지됨
      </li>
    );
  }
  if (row.kind === "subagent") {
    return (
      <li
        className="truncate text-sm text-muted-foreground"
        data-testid="execution-event-row"
      >
        하위 에이전트: {subagentLabel(row.subagentName, row.detail)}
        {row.subagentName?.trim() && row.detail ? ` · ${row.detail}` : ""}
        {usageStatusLabel(row.usageStatus)
          ? ` · ${usageStatusLabel(row.usageStatus)}`
          : ""}
      </li>
    );
  }
  if (!row.finished) {
    return (
      <li
        className="truncate text-sm text-muted-foreground"
        data-testid="execution-event-row"
        data-tool={row.toolName ?? "도구"}
      >
        {toolLabel(row.toolName, false)} · 끝나지 않음
      </li>
    );
  }
  return (
    <li
      className="truncate text-sm text-muted-foreground"
      data-testid="execution-event-row"
      data-tool={row.toolName ?? "도구"}
    >
      {toolLabel(row.toolName, false)} · {formatDuration(row.durationMs ?? 0)}
      {row.detail ? ` · ${row.detail}` : ""}
    </li>
  );
}

/** 사용량 보완은 실행 성공과 별개라 실패 상태와 같은 모양이나 문구를 쓰지 않는다. */
function usageStatusLabel(
  status: Extract<MergedEventRow, { kind: "subagent" }>["usageStatus"],
): string | null {
  if (status === "WAITING") return "수치 확인 중";
  if (status === "UNCONFIRMED") return "사용량 미확인";
  if (status === "RECORDED") return "수치 확인됨";
  return null;
}
