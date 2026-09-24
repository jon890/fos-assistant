import { formatDuration, formatTokens } from "@/lib/format";
import type { ActivityItem, ActivityItemState } from "./activity-state";

const MARKS: Record<ActivityItemState, string> = {
  running: "⟳", done: "✓", failed: "!", stopped: "■",
};
const SPOKEN: Record<ActivityItemState, string> = {
  running: "도는 중", done: "끝남", failed: "실패", stopped: "중지됨",
};

export function ActivityTimeline({ items }: { items: ActivityItem[] }) {
  return (
    <ol className="flex min-w-0 flex-col gap-2" aria-label="작업 과정의 사건">
      {items.map((item) => (
        <li key={item.key} data-testid="activity-item" data-kind={item.kind}
          data-state={item.state} data-step={item.kind === "step" ? item.pairKey ?? undefined : undefined}
          className="flex min-w-0 gap-2 text-xs">
          <span aria-hidden="true" className="w-4 shrink-0 text-center">{MARKS[item.state]}</span>
          <span className="sr-only">{SPOKEN[item.state]}</span>
          <div className="min-w-0 flex-1">
            <div className="flex min-w-0 items-baseline gap-2">
              {item.kind === "subagent" ? <span className="shrink-0 text-muted">하위 에이전트</span> : null}
              <span className="min-w-0 break-words">{item.name}</span>
              {item.durationMs !== null && item.state !== "running" ? (
                <span className="ml-auto shrink-0 text-muted">{formatDuration(item.durationMs)}</span>
              ) : null}
            </div>
            {item.kind === "tool" && item.detail ? (
              <p className="break-words text-muted">{item.detail}</p>
            ) : null}
            {item.kind === "subagent" && (item.model || item.inputTokens !== null || item.outputTokens !== null) ? (
              <p className="break-words text-muted">
                {[item.model, item.inputTokens === null ? null : `입력 ${formatTokens(item.inputTokens)}`,
                  item.outputTokens === null ? null : `출력 ${formatTokens(item.outputTokens)}`]
                  .filter(Boolean).join(" · ")}
              </p>
            ) : null}
          </div>
        </li>
      ))}
    </ol>
  );
}
