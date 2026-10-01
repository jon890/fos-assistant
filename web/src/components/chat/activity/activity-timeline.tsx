import type { ReactNode } from "react";
import {
  Check,
  CircleAlert,
  CircleHelp,
  LoaderCircle,
  Square,
} from "lucide-react";
import { formatDuration } from "@/lib/format";
import {
  activityLabel,
  type ActivityItem,
  type ActivityItemState,
} from "./activity-state";

const ICON_CLASS = "size-3.5";

const MARKS: Record<ActivityItemState, ReactNode> = {
  running: (
    <LoaderCircle
      aria-hidden="true"
      className={`${ICON_CLASS} animate-spin motion-reduce:animate-none`}
    />
  ),
  done: <Check aria-hidden="true" className={ICON_CLASS} />,
  failed: <CircleAlert aria-hidden="true" className={ICON_CLASS} />,
  stopped: (
    <Square aria-hidden="true" className={`${ICON_CLASS} fill-current`} />
  ),
  unfinished: (
    <Square aria-hidden="true" className={`${ICON_CLASS} fill-current`} />
  ),
  "result-missing": <CircleHelp aria-hidden="true" className={ICON_CLASS} />,
};
const SPOKEN: Record<ActivityItemState, string> = {
  running: "실행 중",
  done: "끝남",
  failed: "실패",
  stopped: "중지됨",
  unfinished: "끝나지 않음",
  "result-missing": "결과를 받지 못함",
};

export function ActivityTimeline({ items }: { items: ActivityItem[] }) {
  return (
    <ol className="flex min-w-0 flex-col gap-2" aria-label="작업 과정">
      {items.map((item) => (
        <li
          key={item.key}
          data-testid="activity-item"
          data-kind={item.kind}
          data-state={item.state}
          data-tool={item.kind === "tool" ? item.name : undefined}
          data-step={
            item.kind === "step" ? (item.pairKey ?? undefined) : undefined
          }
          className="flex min-w-0 gap-2 text-xs"
        >
          <span
            aria-hidden="true"
            className="flex h-4 w-4 shrink-0 items-center justify-center"
          >
            {MARKS[item.state]}
          </span>
          {item.state !== "result-missing" ? (
            <span className="sr-only">{SPOKEN[item.state]}</span>
          ) : null}
          <div className="min-w-0 flex-1">
            <div className="flex min-w-0 items-baseline gap-2">
              {item.kind === "subagent" ? (
                <span className="shrink-0 text-muted-foreground">
                  하위 에이전트
                </span>
              ) : null}
              <span className="min-w-0 break-words">{activityLabel(item)}</span>
              {item.durationMs !== null && item.state !== "running" ? (
                <span className="ml-auto shrink-0 text-muted-foreground">
                  {formatDuration(item.durationMs)}
                </span>
              ) : null}
            </div>
            {item.state === "result-missing" ? (
              <p className="text-muted-foreground">{SPOKEN[item.state]}</p>
            ) : null}
            {item.kind === "tool" && item.detail ? (
              <p className="break-words text-muted-foreground">{item.detail}</p>
            ) : null}
          </div>
        </li>
      ))}
    </ol>
  );
}
