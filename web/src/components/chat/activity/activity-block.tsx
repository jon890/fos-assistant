"use client";

import { useEffect, useState } from "react";
import type { ActivitySummary } from "@/lib/chat-event";
import { formatElapsed } from "@/lib/format";
import type { ExecutionTreeResponse } from "@/components/execution/execution-tree";
import { ActivityTimeline } from "./activity-timeline";
import { fromTree, type ActivityItem, type ActivityState } from "./activity-state";

type Props =
  | { mode: "live"; state: ActivityState; slow: boolean; onOpenPanel?(): void }
  | { mode: "saved"; summary: ActivitySummary; executionId: number; onOpenPanel?(): void };

export function ActivityBlock(props: Props) {
  const [expanded, setExpanded] = useState(false);
  const [savedItems, setSavedItems] = useState<ActivityItem[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [loadVersion, setLoadVersion] = useState(0);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (props.mode !== "live") return;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [props.mode]);

  const executionId = props.mode === "saved" ? props.executionId : null;
  useEffect(() => {
    if (!expanded || executionId === null || savedItems !== null) return;
    let active = true;
    fetch(`/api/usage/executions/${executionId}/tree`, { cache: "no-store" })
      .then((response) => {
        if (!response.ok) throw new Error("실행 나무를 읽지 못했다");
        return response.json() as Promise<ExecutionTreeResponse>;
      })
      .then((tree) => {
        if (active) { setSavedItems(fromTree(tree)); setLoadFailed(false); }
      })
      .catch(() => { if (active) setLoadFailed(true); });
    return () => { active = false; };
  }, [expanded, executionId, loadVersion, savedItems]);

  const live = props.mode === "live";
  const latest = live ? props.state.items.findLast((item) => item.state === "running") : null;
  const title = live
    ? `작업 과정${latest ? ` · ${latest.name}` : ""} · ${formatElapsed(now - props.state.startedAt)}`
    : ["작업 과정", props.summary.toolCount ? `도구 ${props.summary.toolCount}` : null,
      props.summary.subagentCount ? `하위 에이전트 ${props.summary.subagentCount}` : null,
      props.summary.durationMs === null ? null : formatElapsed(props.summary.durationMs)]
      .filter(Boolean).join(" · ");
  const items = live ? props.state.items : savedItems;

  return (
    <div data-testid="activity-block" data-mode={props.mode}
      className="min-w-0 rounded-lg border border-border bg-surface text-foreground">
      <button type="button" data-testid="activity-toggle" aria-expanded={expanded}
        onClick={() => setExpanded((value) => !value)}
        className="flex w-full min-w-0 items-center gap-2 px-3 py-2 text-left text-xs">
        <span aria-hidden="true" className="shrink-0">{expanded ? "▾" : "▸"}</span>
        <span className="min-w-0 flex-1 truncate">{title}</span>
      </button>
      {live && props.slow ? (
        <p data-testid="flow-slow-notice" className="px-3 pb-2 text-xs text-muted">
          오래 걸릴 수 있다. 이 화면을 떠나도 된다. 실행은 계속 돌고, 나중에 다시 열면 저장된 답이 보인다.
        </p>
      ) : null}
      {expanded ? (
        <div className="min-w-0 border-t border-border px-3 py-2">
          {items ? <ActivityTimeline items={items} /> : loadFailed ? (
            <p data-testid="activity-load-error" className="text-xs text-muted">
              작업 과정을 읽지 못했다
              <button type="button" className="ml-2 underline" onClick={() => {
                setLoadFailed(false); setLoadVersion((value) => value + 1);
              }}>다시 읽기</button>
            </p>
          ) : <p className="text-xs text-muted">작업 과정을 읽는 중</p>}
          {props.onOpenPanel ? (
            <div className="mt-2 text-right">
              <button type="button" data-testid="activity-open-panel" onClick={props.onOpenPanel}
                className="text-xs text-muted underline underline-offset-4">나무로 보기 →</button>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
