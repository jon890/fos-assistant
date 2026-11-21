"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { describeFailure } from "@/components/error-message";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { PageFrame } from "@/components/ui/page-frame";
import { ListSkeleton, LoadFailure } from "@/components/ui/page-state";
import { agentLabel } from "@/lib/format";
import { PAGE_HEADS } from "@/lib/page-head";
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

/** 목록을 읽는다. 읽지 못하면 화면에 보일 문장을 돌려준다. */
async function readTasks(): Promise<TaskView[] | string> {
  try {
    const response = await fetchTasks();
    if (!response.ok) return await describeFailure(response);
    return (await response.json()) as TaskView[];
  } catch {
    return "예약 작업을 읽지 못했어요. 잠시 뒤 다시 시도해 주세요.";
  }
}

/** 내 예약 작업 목록이다. 목록은 화면이 열린 뒤 브라우저가 읽는다. */
export function TaskList() {
  const [tasks, setTasks] = useState<TaskView[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    void readTasks().then((result) => {
      if (typeof result === "string") setError(result);
      else {
        setTasks(result);
        setError(null);
      }
    });
  }, []);

  useEffect(load, [load]);

  /** 다시 읽는 동안 뼈대를 보여 눌린 것이 보이게 한다. */
  function retry() {
    setError(null);
    setTasks(null);
    load();
  }

  return (
    <PageFrame
      {...PAGE_HEADS.tasks}
      action={
        <Link href="/tasks/new" className={buttonVariants({ size: "sm" })}>
          새 작업
        </Link>
      }
    >
      {error ? (
        <LoadFailure message={error} onRetry={retry} />
      ) : tasks === null ? (
        <ListSkeleton />
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
                    {agentLabel(task.agentName)} ·{" "}
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
    </PageFrame>
  );
}
