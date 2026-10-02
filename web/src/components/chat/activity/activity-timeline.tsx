"use client";

import type { ReactNode } from "react";
import {
  Check,
  CircleAlert,
  CircleHelp,
  LoaderCircle,
  Square,
} from "lucide-react";
import { cn } from "cn";
import { formatSeconds } from "@/lib/format";
import { isReadableDetail } from "@/lib/tool-label";
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
/** 화면 낭독기에만 읽히는 상태다. 실패와 결과 누락은 글자로 보이므로 여기 없다. */
const SPOKEN: Partial<Record<ActivityItemState, string>> = {
  running: "실행 중",
  done: "끝남",
  stopped: "중지됨",
  unfinished: "끝나지 않음",
};

function markColor(state: ActivityItemState): string {
  return state === "done"
    ? "text-success"
    : state === "failed"
      ? "text-destructive"
      : "text-muted-foreground";
}

export function ActivityTimeline({ items }: { items: ActivityItem[] }) {
  return (
    <ol className="flex min-w-0 flex-col gap-2" aria-label="작업 과정">
      {items.map((item) => {
        const seconds =
          item.durationMs !== null && item.state !== "running"
            ? formatSeconds(item.durationMs)
            : null;
        const spoken = SPOKEN[item.state];
        return (
          <li
            key={item.key}
            data-testid="activity-item"
            data-kind={item.kind}
            data-state={item.state}
            data-tool={item.kind === "tool" ? item.name : undefined}
            data-step={
              item.kind === "step" ? (item.pairKey ?? undefined) : undefined
            }
            className="flex min-w-0 gap-2 text-sm text-foreground-soft"
          >
            <span
              aria-hidden="true"
              className={cn(
                "flex h-5 w-4 shrink-0 items-center justify-center",
                markColor(item.state),
              )}
            >
              {MARKS[item.state]}
            </span>
            {spoken ? <span className="sr-only">{spoken}</span> : null}
            <div className="min-w-0 flex-1">
              <div className="flex min-w-0 items-baseline gap-2">
                {item.kind === "subagent" ? (
                  <span className="shrink-0 text-muted-foreground">도우미</span>
                ) : null}
                <span className="min-w-0 break-words">
                  {activityLabel(item)}
                </span>
                {item.state === "failed" ? (
                  <span className="shrink-0 text-destructive">실패</span>
                ) : null}
                {seconds !== null ? (
                  <span className="ml-auto shrink-0 text-muted-foreground tabular-nums">
                    {seconds}
                  </span>
                ) : null}
              </div>
              {item.state === "result-missing" ? (
                <p className="text-muted-foreground">결과를 받지 못함</p>
              ) : null}
              {/* 명령과 도구 결과의 원본은 JSON 이나 내부 경로라 대화 화면에서는 그리지 않는다. */}
              {item.kind === "tool" &&
              item.detail &&
              isReadableDetail(item.name) ? (
                <p className="break-words text-muted-foreground">
                  {item.detail}
                </p>
              ) : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
