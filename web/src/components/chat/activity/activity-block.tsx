"use client";

import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { ChevronRight, CircleAlert, CircleCheck, Square } from "lucide-react";
import { cn } from "cn";
import type { ActivitySummary } from "@/lib/chat-event";
import { formatElapsed, formatSeconds } from "@/lib/format";
import type { ExecutionTreeResponse } from "@/components/execution/execution-tree";
import { ActivityTimeline } from "./activity-timeline";
import {
  activityLabel,
  activityOutcome,
  activitySummaryLabel,
  fromTree,
  type ActivityItem,
  type ActivityOutcome,
  type ActivityState,
} from "./activity-state";

/** 맨 아래에서 이 값(px) 안이면 사용자가 맨 아래를 보고 있는 것으로 본다. */
const BOTTOM_TOLERANCE_PX = 16;

const OUTCOME_ICON_CLASS = "size-4 shrink-0";

/** 끝난 블록의 접힌 줄 앞에 두는 상태 아이콘이다. */
function OutcomeIcon({ outcome }: { outcome: ActivityOutcome }) {
  if (outcome === "stopped") {
    return (
      <Square
        aria-hidden="true"
        className={cn(OUTCOME_ICON_CLASS, "text-muted-foreground")}
      />
    );
  }
  if (outcome === "failed") {
    return (
      <CircleAlert
        aria-hidden="true"
        className={cn(OUTCOME_ICON_CLASS, "text-destructive")}
      />
    );
  }
  return (
    <CircleCheck
      aria-hidden="true"
      className={cn(OUTCOME_ICON_CLASS, "text-success")}
    />
  );
}

type Props =
  | {
      mode: "live";
      state: ActivityState;
      slow: boolean;
      expanded: boolean;
      onExpandedChange(value: boolean): void;
      onOpenPanel?(): void;
    }
  | {
      mode: "saved";
      summary: ActivitySummary;
      executionId: number;
      initialExpanded?: boolean;
      cancelled?: boolean;
      onOpenPanel?(): void;
    };

export function ActivityBlock(props: Props) {
  const [savedExpanded, setSavedExpanded] = useState(
    props.mode === "saved" && props.initialExpanded === true,
  );
  const [savedItems, setSavedItems] = useState<ActivityItem[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [loadVersion, setLoadVersion] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const scrollRef = useRef<HTMLDivElement>(null);
  const atBottomRef = useRef(true);

  useEffect(() => {
    if (props.mode !== "live" || props.state.endedAt !== null) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [props.mode, props.mode === "live" ? props.state.endedAt : null]);

  const expanded = props.mode === "live" ? props.expanded : savedExpanded;
  const executionId = props.mode === "saved" ? props.executionId : null;
  const cancelled = props.mode === "saved" && props.cancelled === true;
  useEffect(() => {
    if (!expanded || executionId === null || savedItems !== null) return;
    let active = true;
    fetch(`/api/usage/executions/${executionId}/tree`, { cache: "no-store" })
      .then((response) => {
        if (!response.ok) throw new Error("작업 과정을 불러오지 못했어요");
        return response.json() as Promise<ExecutionTreeResponse>;
      })
      .then((tree) => {
        if (active) {
          setSavedItems(fromTree(tree, { cancelled }));
          setLoadFailed(false);
        }
      })
      .catch(() => {
        if (active) setLoadFailed(true);
      });
    return () => {
      active = false;
    };
  }, [expanded, executionId, cancelled, loadVersion, savedItems]);

  const live = props.mode === "live";
  const running = props.mode === "live" && props.state.endedAt === null;
  const latest = running
    ? props.state.items.findLast((item) => item.state === "running")
    : null;
  // 끝난 블록의 한 줄은 수로 고르는 고정 문장이다. 수와 모델과 토큰은 대화에 그리지 않는다.
  const outcome: ActivityOutcome =
    props.mode === "live"
      ? activityOutcome(props.state.items)
      : cancelled
        ? "stopped"
        : "done";
  const summaryLabel = activitySummaryLabel(
    props.mode === "live"
      ? {
          toolCount: props.state.items.filter((item) => item.kind === "tool")
            .length,
          subagentCount: props.state.items.filter(
            (item) => item.kind === "subagent",
          ).length,
        }
      : props.summary,
    outcome,
  );
  const durationMs =
    props.mode === "saved"
      ? props.summary.durationMs
      : props.state.endedAt === null
        ? null
        : props.state.endedAt - props.state.startedAt;
  // 1초가 안 되는 걸린 시간은 그리지 않는다.
  const duration = durationMs === null ? null : formatSeconds(durationMs);
  const items = live ? props.state.items : savedItems;
  const following = running;

  // 도는 중이고 사용자가 맨 아래를 보고 있을 때만 새 줄을 따라간다. 끝난 답은 맨 위부터 보인다.
  useLayoutEffect(() => {
    const box = scrollRef.current;
    if (!expanded) {
      // 접어도 스크롤 상자는 남는다. 다시 펼치면 처음 펼친 것처럼 보이도록 맨 위로 돌리고 맨 아래를 보는 것으로 되돌린다.
      if (box) box.scrollTop = 0;
      atBottomRef.current = true;
      return;
    }
    if (!following || !box || !atBottomRef.current) return;
    box.scrollTop = box.scrollHeight;
  }, [following, expanded, items?.length]);

  return (
    <div
      data-testid="activity-block"
      data-mode={props.mode}
      className="min-w-0 rounded-lg border border-border bg-card text-foreground-soft"
    >
      <button
        type="button"
        data-testid="activity-toggle"
        aria-expanded={expanded}
        onClick={() =>
          props.mode === "live"
            ? props.onExpandedChange(!expanded)
            : setSavedExpanded(!expanded)
        }
        className="flex min-h-11 w-full min-w-0 items-center gap-2 px-3 text-left text-[0.8125rem] font-medium"
      >
        <span className="sr-only">작업 과정: </span>
        {props.mode === "live" && props.state.endedAt === null ? (
          <>
            <span
              data-testid="activity-signal"
              aria-hidden="true"
              className="size-2 shrink-0 rounded-full bg-signal ring-2 ring-pill"
            />
            <span className="min-w-0 flex-1 truncate">
              {latest ? activityLabel(latest) : "준비하고 있어요"}
            </span>
            <span className="shrink-0 font-normal text-muted-foreground tabular-nums">
              {formatElapsed(now - props.state.startedAt)}
            </span>
          </>
        ) : (
          <>
            <OutcomeIcon outcome={outcome} />
            <span className="min-w-0 flex-1 truncate">{summaryLabel}</span>
          </>
        )}
        <ChevronRight
          aria-hidden="true"
          className={cn(
            "size-4 shrink-0 text-muted-foreground transition-transform duration-base ease-out",
            expanded && "rotate-90",
          )}
        />
      </button>
      {live && props.slow && props.state.endedAt === null ? (
        <p
          data-testid="flow-slow-notice"
          className="px-3 pb-2 text-xs text-muted-foreground"
        >
          오래 걸릴 수 있어요. 이 화면을 떠나도 실행은 계속돼요. 나중에 대화를
          다시 열면 저장된 답을 볼 수 있어요.
        </p>
      ) : null}
      {/* 높이가 움직이도록 접힌 동안에도 그려 둔다. 접힌 내용에는 초점과 화면 읽기가 닿지 않게 한다. */}
      <div className="collapsible" data-open={expanded}>
        <div aria-hidden={!expanded} inert={!expanded}>
          <div className="min-w-0 border-t border-border px-3 py-2">
            {items ? (
              <div
                ref={scrollRef}
                data-testid="activity-scroll"
                className="max-h-64 overflow-y-auto overscroll-contain"
                onScroll={(event) => {
                  // 접을 때 맨 위로 돌린 것은 사용자가 올려 읽은 것이 아니다.
                  if (!expanded) return;
                  const box = event.currentTarget;
                  atBottomRef.current =
                    box.scrollHeight - box.scrollTop - box.clientHeight <=
                    BOTTOM_TOLERANCE_PX;
                }}
              >
                <ActivityTimeline items={items} />
              </div>
            ) : loadFailed ? (
              <p
                data-testid="activity-load-error"
                className="text-xs text-muted-foreground"
              >
                작업 과정을 읽지 못했어요
                <button
                  type="button"
                  className="ml-2 underline"
                  onClick={() => {
                    setLoadFailed(false);
                    setLoadVersion((value) => value + 1);
                  }}
                >
                  다시 읽기
                </button>
              </p>
            ) : (
              <p className="text-xs text-muted-foreground">
                작업 과정을 읽고 있어요
              </p>
            )}
            {duration !== null || props.onOpenPanel ? (
              <div className="mt-2 flex min-w-0 items-center gap-2 text-xs text-muted-foreground">
                {duration !== null ? (
                  <span
                    data-testid="activity-duration"
                    className="tabular-nums"
                  >
                    걸린 시간 {duration}
                  </span>
                ) : null}
                {props.onOpenPanel ? (
                  <button
                    type="button"
                    data-testid="activity-open-panel"
                    onClick={props.onOpenPanel}
                    className="ml-auto underline underline-offset-4"
                  >
                    자세히 보기
                  </button>
                ) : null}
              </div>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  );
}
