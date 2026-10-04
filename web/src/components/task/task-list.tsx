"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { describeFailure } from "@/components/error-message";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Notice } from "@/components/ui/notice";
import { Skeleton } from "@/components/ui/skeleton";
import { fetchTasks } from "@/lib/task-api";
import { describeSchedule, type TaskView } from "@/lib/task";

export function formatDateTime(value: string): string {
  return new Date(value).toLocaleString("ko-KR", {
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** 내 예약 작업 목록이다. 목록은 화면이 열린 뒤 브라우저가 읽는다. */
export function TaskList() {
  const [tasks, setTasks] = useState<TaskView[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const response = await fetchTasks();
        if (cancelled) return;
        if (!response.ok) {
          setError(await describeFailure(response));
          return;
        }
        setTasks((await response.json()) as TaskView[]);
      } catch {
        if (!cancelled)
          setError("예약 작업을 읽지 못했어요. 잠시 뒤 다시 시도해 주세요.");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="mx-auto w-full max-w-3xl">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h1 className="text-xl font-semibold">예약 작업</h1>
        <Link href="/tasks/new" className={buttonVariants({ size: "sm" })}>
          새 작업
        </Link>
      </div>
      {error ? (
        <Notice variant="error" role="alert" className="mb-4">
          {error}
        </Notice>
      ) : null}
      {tasks === null ? (
        error ? null : (
          <div className="flex flex-col gap-2">
            <Skeleton className="h-16" />
            <Skeleton className="h-16" />
          </div>
        )
      ) : tasks.length === 0 ? (
        <EmptyState
          title="아직 예약 작업이 없어요."
          description="정한 시각에 에이전트가 지시대로 일해요."
        />
      ) : (
        <ul className="flex flex-col gap-2">
          {tasks.map((task) => (
            <li key={task.id} data-testid="task-item">
              <Link
                href={`/tasks/${task.id}`}
                prefetch={false}
                className="flex items-start gap-3 rounded-md border border-border px-4 py-3 hover:bg-accent"
              >
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-semibold">
                    {task.title}
                  </span>
                  <span className="mt-0.5 block text-sm text-muted-foreground">
                    {task.agentName ?? "에이전트 없음"} ·{" "}
                    {describeSchedule(task.schedule)}
                  </span>
                  <span className="mt-0.5 block text-xs text-muted-foreground">
                    {task.nextFireAt
                      ? `다음 실행 ${formatDateTime(task.nextFireAt)}`
                      : "다음 실행 없음"}
                  </span>
                </span>
                <Badge
                  variant={task.state === "ACTIVE" ? "success" : "outline"}
                >
                  {task.state === "ACTIVE" ? "켜짐" : "멈춤"}
                </Badge>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
