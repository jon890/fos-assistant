"use client";

import { useEffect, useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import type { ActivitySummary } from "@/lib/chat-event";
import { formatElapsed } from "@/lib/format";
import type { ExecutionTreeResponse } from "@/components/execution/execution-tree";
import { ActivityTimeline } from "./activity-timeline";
import { fromTree, type ActivityItem, type ActivityState } from "./activity-state";

type Props =
  | { mode: "live"; state: ActivityState; slow: boolean; expanded: boolean;
      onExpandedChange(value: boolean): void; onOpenPanel?(): void }
  | { mode: "saved"; summary: ActivitySummary; executionId: number; initialExpanded?: boolean; cancelled?: boolean;
      onOpenPanel?(): void };

export function ActivityBlock(props: Props) {
  const [savedExpanded, setSavedExpanded] = useState(props.mode === "saved" && props.initialExpanded === true);
  const [savedItems, setSavedItems] = useState<ActivityItem[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [loadVersion, setLoadVersion] = useState(0);
  const [now, setNow] = useState(() => Date.now());

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
        if (active) { setSavedItems(fromTree(tree, { cancelled })); setLoadFailed(false); }
      })
      .catch(() => { if (active) setLoadFailed(true); });
    return () => { active = false; };
  }, [expanded, executionId, cancelled, loadVersion, savedItems]);

  const live = props.mode === "live";
  const latest = live && props.state.endedAt === null
    ? props.state.items.findLast((item) => item.state === "running") : null;
  const title = live
    ? props.state.endedAt === null
      ? `작업 과정${latest ? ` · ${latest.name}` : ""} · ${formatElapsed(now - props.state.startedAt)}`
      : ["작업 과정", `도구 ${props.state.items.filter((item) => item.kind === "tool").length}`,
        `하위 에이전트 ${props.state.items.filter((item) => item.kind === "subagent").length}`,
        formatElapsed(props.state.endedAt - props.state.startedAt)].join(" · ")
    : ["작업 과정", props.summary.toolCount ? `도구 ${props.summary.toolCount}` : null,
      props.summary.subagentCount ? `하위 에이전트 ${props.summary.subagentCount}` : null,
      props.summary.durationMs === null ? null : formatElapsed(props.summary.durationMs)]
      .filter(Boolean).join(" · ");
  const items = live ? props.state.items : savedItems;

  return (
    <div data-testid="activity-block" data-mode={props.mode}
      className="min-w-0 rounded-lg border border-border bg-muted text-foreground">
      <button type="button" data-testid="activity-toggle" aria-expanded={expanded}
        onClick={() => props.mode === "live" ? props.onExpandedChange(!expanded) : setSavedExpanded(!expanded)}
        className="flex w-full min-w-0 items-center gap-2 px-3 py-2 text-left text-xs">
        {expanded
          ? <ChevronDown aria-hidden="true" className="size-4 shrink-0" />
          : <ChevronRight aria-hidden="true" className="size-4 shrink-0" />}
        <span className="min-w-0 flex-1 truncate">{title}</span>
      </button>
      {live && props.slow && props.state.endedAt === null ? (
        <p data-testid="flow-slow-notice" className="px-3 pb-2 text-xs text-muted-foreground">
          오래 걸릴 수 있어요. 이 화면을 떠나도 실행은 계속돼요. 나중에 대화를 다시 열면 저장된 답을 볼 수 있어요.
        </p>
      ) : null}
      {expanded ? (
        <div className="min-w-0 border-t border-border px-3 py-2">
          {items ? <ActivityTimeline items={items} /> : loadFailed ? (
            <p data-testid="activity-load-error" className="text-xs text-muted-foreground">
              작업 과정을 읽지 못했어요
              <button type="button" className="ml-2 underline" onClick={() => {
                setLoadFailed(false); setLoadVersion((value) => value + 1);
              }}>다시 읽기</button>
            </p>
          ) : <p className="text-xs text-muted-foreground">작업 과정을 읽고 있어요</p>}
          {props.onOpenPanel ? (
            <div className="mt-2 text-right">
              <button type="button" data-testid="activity-open-panel" onClick={props.onOpenPanel}
                className="text-xs text-muted-foreground underline underline-offset-4">작업 과정 자세히 보기</button>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
