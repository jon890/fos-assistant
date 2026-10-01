import { describeError } from "@/components/error-message";
import { formatDuration, subagentLabel } from "@/lib/format";
import { isReadableDetail, toolLabel } from "@/lib/tool-label";

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
    }
  | { kind: "error"; key: number; detail: string | null }
  | { kind: "cancelled"; key: number };

/**
 * 실패 줄의 문구다. `detail` 에는 오류 코드가 온다.
 *
 * <p>코드 원문은 관리자에게만 안내 문구 뒤에 붙인다. 그 밖의 사용자에게는 안내 문구만 보이고,
 * 문구가 정해지지 않은 코드는 일반 문구로 바꾼다.
 */
function failureText(detail: string | null, isAdmin: boolean): string {
  if (!detail) return "실행 실패";
  if (!isAdmin)
    return `실행 실패: ${describeError(detail, "실행을 마치지 못했어요.")}`;
  const described = describeError(detail, detail);
  return described === detail
    ? `실행 실패: ${detail}`
    : `실행 실패: ${described} (${detail})`;
}

export function ExecutionEventRow({
  row,
  isAdmin,
}: {
  row: MergedEventRow;
  isAdmin: boolean;
}) {
  if (row.kind === "error") {
    return (
      <li
        className="truncate text-sm text-destructive"
        data-testid="execution-event-row"
      >
        {failureText(row.detail, isAdmin)}
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
        도우미: {subagentLabel(row.subagentName, row.detail)}
        {row.subagentName?.trim() && row.detail ? ` · ${row.detail}` : ""}
      </li>
    );
  }
  const readable = isReadableDetail(row.toolName);
  return (
    <li data-testid="execution-event-row" data-tool={row.toolName ?? "도구"}>
      <p className="truncate text-sm text-muted-foreground">
        {toolLabel(row.toolName, false)} ·{" "}
        {row.finished ? formatDuration(row.durationMs ?? 0) : "끝나지 않음"}
        {row.finished && row.detail && readable ? ` · ${row.detail}` : ""}
      </p>
      {isAdmin && row.detail && !readable ? (
        // 명령과 도구 결과의 원본은 JSON 이나 내부 경로다. 관리자에게도 접어 두고 펼칠 때만 보인다.
        // 줄의 `truncate` 가 자르지 않게 줄 아래 따로 둔다.
        <details
          data-testid="activity-raw"
          className="min-w-0 text-xs text-muted-foreground"
        >
          <summary className="cursor-pointer">원본 보기</summary>
          <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-all rounded-sm bg-muted p-2 font-mono text-xs">
            {`${row.toolName ?? "도구"}\n${row.detail}`}
          </pre>
        </details>
      ) : null}
    </li>
  );
}
