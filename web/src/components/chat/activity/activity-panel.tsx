"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { ExecutionTree, type ExecutionTreeResponse } from "@/components/execution/execution-tree";
import { ActivityTimeline } from "./activity-timeline";
import type { ActivityState } from "./activity-state";

export type ActivityPanelTarget =
  | { mode: "live"; state: ActivityState }
  | { mode: "saved"; executionId: number };

type Props = { target: ActivityPanelTarget; onClose(): void };

export function ActivityPanel({ target, onClose }: Props) {
  const [loaded, setLoaded] = useState<{ executionId: number; tree: ExecutionTreeResponse } | null>(null);
  const [failedId, setFailedId] = useState<number | null>(null);
  const [retry, setRetry] = useState(0);
  const executionId = target.mode === "saved" ? target.executionId : null;

  useEffect(() => {
    if (executionId === null) return;
    let active = true;
    fetch(`/api/usage/executions/${executionId}/tree`, { cache: "no-store" })
      .then((response) => {
        if (!response.ok) throw new Error("실행 나무를 읽지 못했다");
        return response.json() as Promise<ExecutionTreeResponse>;
      })
      .then((value) => { if (active) { setLoaded({ executionId, tree: value }); setFailedId(null); } })
      .catch(() => { if (active) setFailedId(executionId); });
    return () => { active = false; };
  }, [executionId, retry]);

  return (
    <aside data-testid="activity-panel" role="complementary" aria-label="작업 과정"
      className="fixed inset-0 z-50 flex min-w-0 flex-col border-l border-border bg-background md:absolute md:inset-y-0 md:left-auto md:w-96 md:shadow-xl lg:relative lg:inset-auto lg:z-auto lg:w-96 lg:shrink-0 lg:shadow-none">
      <header className="flex min-w-0 items-center gap-2 border-b border-border px-4 py-3">
        <h2 className="min-w-0 flex-1 truncate text-sm font-semibold">작업 과정</h2>
        {executionId !== null ? (
          <Link href={`/executions/${executionId}`} data-testid="flow-tree-link"
            className="shrink-0 text-xs text-muted underline underline-offset-4">전체 화면으로 보기</Link>
        ) : null}
        <button type="button" aria-label="작업 과정 닫기" onClick={onClose}
          className="shrink-0 rounded-md px-2 py-1 text-sm hover:bg-surface">✕</button>
      </header>
      <div className="min-h-0 min-w-0 flex-1 overflow-y-auto overflow-x-hidden p-4">
        {target.mode === "live" ? <ActivityTimeline items={target.state.items} /> :
          failedId === executionId ? <p className="text-sm text-muted">실행 나무를 읽지 못했다
            <button type="button" className="ml-2 underline" onClick={() => {
              setFailedId(null); setLoaded(null); setRetry((value) => value + 1);
            }}>다시 읽기</button>
          </p> : loaded?.executionId === executionId ? <ExecutionTree tree={loaded.tree} /> : (
            <div aria-label="실행 나무를 읽는 중" className="flex flex-col gap-3">
              <Skeleton className="h-8" /><Skeleton className="h-20" /><Skeleton className="h-20" />
            </div>
          )}
      </div>
    </aside>
  );
}
