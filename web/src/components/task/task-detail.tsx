"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { describeFailure } from "@/components/error-message";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Skeleton } from "@/components/ui/skeleton";
import {
  deleteTask,
  fetchTask,
  fetchTaskRuns,
  pauseTask,
  resumeTask,
} from "@/lib/task-api";
import {
  runReasonText,
  type TaskRunStatus,
  type TaskRunView,
  type TaskView,
} from "@/lib/task";
import { formatDateTime } from "./task-list";
import { TaskForm } from "./task-form";

const RUN_LIMIT = 20;

const STATUS_TEXTS: Record<TaskRunStatus, string> = {
  QUEUED: "기다리는 중",
  RUNNING: "도는 중",
  SUCCEEDED: "마쳤어요",
  FAILED: "실패했어요",
  CANCELLED: "멈췄어요",
  SKIPPED: "건너뛰었어요",
};

/** 예약 작업 하나다. 고치기, 멈추기와 다시 켜기, 지우기, 최근 실행을 한 화면에서 한다. */
export function TaskDetail({ taskId }: { taskId: string }) {
  const router = useRouter();
  const [task, setTask] = useState<TaskView | null>(null);
  const [runs, setRuns] = useState<TaskRunView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [toggling, setToggling] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const loadRuns = useCallback(async () => {
    try {
      const response = await fetchTaskRuns(taskId, RUN_LIMIT);
      if (response.ok) setRuns((await response.json()) as TaskRunView[]);
    } catch {
      // 실행 목록을 읽지 못해도 작업 화면은 쓸 수 있다.
    }
  }, [taskId]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const response = await fetchTask(taskId);
        if (cancelled) return;
        if (!response.ok) {
          setError(await describeFailure(response));
          return;
        }
        setTask((await response.json()) as TaskView);
        await loadRuns();
      } catch {
        if (!cancelled)
          setError("작업을 읽지 못했어요. 잠시 뒤 다시 시도해 주세요.");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [taskId, loadRuns]);

  async function toggle() {
    if (!task || toggling) return;
    setToggling(true);
    setError(null);
    try {
      const response =
        task.state === "ACTIVE"
          ? await pauseTask(task.id)
          : await resumeTask(task.id);
      if (!response.ok) {
        setError(await describeFailure(response));
        return;
      }
      setTask((await response.json()) as TaskView);
    } catch {
      setError("작업 상태를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    } finally {
      setToggling(false);
    }
  }

  async function confirmDelete() {
    if (!task) return;
    setDeleting(true);
    try {
      const response = await deleteTask(task.id);
      if (!response.ok) {
        setError(await describeFailure(response));
        return;
      }
      router.push("/tasks");
    } catch {
      setError("작업을 지우지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    } finally {
      setDeleting(false);
      setConfirmingDelete(false);
    }
  }

  return (
    <div className="mx-auto w-full max-w-3xl">
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <h1 className="min-w-0 flex-1 truncate text-xl font-semibold">
          {task?.title ?? "예약 작업"}
        </h1>
        {task ? (
          <>
            <Badge variant={task.state === "ACTIVE" ? "success" : "outline"}>
              {task.state === "ACTIVE" ? "켜짐" : "멈춤"}
            </Badge>
            <Button
              variant="outline"
              size="sm"
              loading={toggling}
              loadingText="바꾸는 중"
              onClick={() => void toggle()}
            >
              {task.state === "ACTIVE" ? "멈추기" : "다시 켜기"}
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setConfirmingDelete(true)}
            >
              지우기
            </Button>
          </>
        ) : null}
      </div>
      {error ? (
        <Notice variant="error" role="alert" className="mb-4">
          {error}
        </Notice>
      ) : null}
      {task ? (
        <>
          <TaskForm
            task={task}
            onSaved={(saved) => {
              setTask(saved);
              void loadRuns();
            }}
          />
          <section className="mt-8">
            <h2 className="mb-2 text-base font-semibold">최근 실행</h2>
            {runs.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                아직 실행한 기록이 없어요.
              </p>
            ) : (
              <ul className="flex flex-col gap-2">
                {runs.map((run) => {
                  const reason = runReasonText(run.reason);
                  return (
                    <li
                      key={run.id}
                      data-testid="task-run"
                      className="flex items-start gap-3 rounded-md border border-border px-4 py-3"
                    >
                      <span className="min-w-0 flex-1">
                        <span className="block text-sm font-medium">
                          {formatDateTime(run.scheduledFor)} ·{" "}
                          {STATUS_TEXTS[run.status]}
                        </span>
                        {reason ? (
                          <span className="mt-0.5 block text-sm text-muted-foreground">
                            {reason}
                          </span>
                        ) : null}
                      </span>
                      {run.conversationId ? (
                        <Link
                          href={`/chat/${run.conversationId}`}
                          prefetch={false}
                          className="shrink-0 text-sm underline underline-offset-4"
                        >
                          대화 보기
                        </Link>
                      ) : null}
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        </>
      ) : error ? null : (
        <div className="flex flex-col gap-2">
          <Skeleton className="h-10" />
          <Skeleton className="h-10" />
          <Skeleton className="h-24" />
        </div>
      )}
      <AlertDialog
        open={confirmingDelete}
        onOpenChange={(open) => {
          if (!open && !deleting) setConfirmingDelete(false);
        }}
      >
        <AlertDialogContent>
          <AlertDialogTitle>작업 지우기</AlertDialogTitle>
          <AlertDialogDescription className="text-foreground">
            {task?.title} 작업을 지워요. 이미 만들어진 대화는 남아요.
          </AlertDialogDescription>
          <AlertDialogFooter>
            <AlertDialogCancel asChild>
              <Button variant="outline" disabled={deleting}>
                취소
              </Button>
            </AlertDialogCancel>
            <Button
              variant="destructive"
              loading={deleting}
              loadingText="지우는 중"
              onClick={() => void confirmDelete()}
            >
              지우기
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
