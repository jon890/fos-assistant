"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { X } from "lucide-react";
import { cn } from "cn";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import { useMediaQuery } from "@/components/ui/use-media-query";
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

  // lg 이상은 대화 옆에 붙어 대화를 계속 쓸 수 있다. 그보다 좁으면 Sheet 로 대화를 덮는다.
  // 첫 그림에서 폭을 모르면(null) 옆에 붙는 모양으로 그린다.
  const wide = useMediaQuery("(min-width: 1024px)");

  const header = (title: React.ReactNode) => (
    <header className="flex min-w-0 items-center gap-2 border-b border-border px-4 py-3">
      {title}
      {executionId !== null ? (
        <Link href={`/executions/${executionId}`} data-testid="flow-tree-link"
          className="shrink-0 text-xs text-muted-foreground underline underline-offset-4">전체 화면으로 보기</Link>
      ) : null}
      <TooltipButton label="작업 과정 닫기" onClick={onClose}><X aria-hidden="true" /></TooltipButton>
    </header>
  );
  const body = (
    <div className="min-h-0 min-w-0 flex-1 overflow-y-auto overflow-x-hidden p-4">
      {target.mode === "live" ? <ActivityTimeline items={target.state.items} /> :
        failedId === executionId ? <p className="text-sm text-muted-foreground">실행 나무를 읽지 못했다
          <button type="button" className="ml-2 underline" onClick={() => {
            setFailedId(null); setLoaded(null); setRetry((value) => value + 1);
          }}>다시 읽기</button>
        </p> : loaded?.executionId === executionId ? <ExecutionTree tree={loaded.tree} /> : (
          <div aria-label="실행 나무를 읽는 중" className="flex flex-col gap-3">
            <Skeleton className="h-8" /><Skeleton className="h-20" /><Skeleton className="h-20" />
          </div>
        )}
    </div>
  );
  const titleClass = "min-w-0 flex-1 truncate text-sm font-semibold";

  if (wide !== false) {
    return (
      <aside data-testid="activity-panel" role="complementary" aria-label="작업 과정"
        className={cn("relative flex w-96 min-w-0 shrink-0 flex-col", "border-l border-border bg-background")}>
        {header(<h2 className={titleClass}>작업 과정</h2>)}
        {body}
      </aside>
    );
  }

  return (
    <Sheet open onOpenChange={(open) => { if (!open) onClose(); }}>
      {/* 닫기 단추는 머리의 「작업 과정 닫기」 하나만 둔다. */}
      <SheetContent side="right" data-testid="activity-panel" showCloseButton={false} aria-describedby={undefined}
        onOpenAutoFocus={(event) => {
          // 첫 단추에 초점을 주면 그 단추의 Tooltip 이 열려 첫 Esc 를 Tooltip 이 가져간다. 패널 자체에 초점을 둔다.
          event.preventDefault();
          if (event.target instanceof HTMLElement) event.target.focus();
        }}
        className={cn("min-w-0 gap-0 border-border bg-background",
          // md 미만은 화면 전체, md 부터는 w-96 으로 겹친다.
          "data-[side=right]:w-full data-[side=right]:sm:max-w-none data-[side=right]:md:w-96")}>
        {header(<SheetTitle className={titleClass}>작업 과정</SheetTitle>)}
        {body}
      </SheetContent>
    </Sheet>
  );
}
