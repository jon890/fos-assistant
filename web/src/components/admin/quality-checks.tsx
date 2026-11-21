"use client";

import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Notice } from "@/components/ui/notice";
import { PageFrame } from "@/components/ui/page-frame";
import { ListSkeleton, LoadFailure } from "@/components/ui/page-state";
import { describeAdminError } from "@/components/error-message";
import { EvaluationUserPanel } from "@/components/admin/evaluation-user-panel";
import { QualityCheckRow } from "@/components/admin/quality-check-row";
import {
  fetchEvaluationUser,
  fetchQualityChecks,
  retryQualityCheck,
  startQualityCheck,
  type EvaluationUserStatus,
  type QualityCheck,
  type QualityCheckPage,
} from "@/lib/quality-api";

/** 도는 확인이 있을 때 목록을 다시 읽는 간격이다. 기능 하나가 보통 1~3분이라 10초면 충분하다. */
const POLL_MS = 10_000;

type Loaded = {
  evaluation: EvaluationUserStatus;
  checks: QualityCheck[];
  nextCursor: string | null;
};

type Message = { error: boolean; text: string };

async function readJson<T>(response: Promise<Response>): Promise<T | null> {
  try {
    const resolved = await response;
    return resolved.ok ? ((await resolved.json()) as T) : null;
  } catch {
    return null;
  }
}

/** 처음 쪽과 평가 사용자 상태를 함께 읽는다. 둘 중 하나라도 읽지 못하면 `null` 이다. */
async function readFirst(): Promise<Loaded | null> {
  const [evaluation, page] = await Promise.all([
    readJson<EvaluationUserStatus>(fetchEvaluationUser()),
    readJson<QualityCheckPage>(fetchQualityChecks()),
  ]);
  if (evaluation === null || page === null) return null;
  return { evaluation, checks: page.items, nextCursor: page.nextCursor };
}

/** 다시 읽은 첫 쪽을 지금 목록에 합친다. 「더 보기」 로 펼친 뒤쪽 기록은 남긴다. */
function merged(current: Loaded, page: QualityCheckPage): Loaded {
  const fresh = new Set(page.items.map((check) => check.publicId));
  const older = current.checks.filter((check) => !fresh.has(check.publicId));
  return {
    ...current,
    checks: [...page.items, ...older],
    nextCursor: older.length > 0 ? current.nextCursor : page.nextCursor,
  };
}

/** 다음 쪽을 뒤에 붙인다. 이미 있는 줄은 다시 붙이지 않는다. */
function appended(current: Loaded, page: QualityCheckPage): Loaded {
  const seen = new Set(current.checks.map((check) => check.publicId));
  return {
    ...current,
    checks: [
      ...current.checks,
      ...page.items.filter((check) => !seen.has(check.publicId)),
    ],
    nextCursor: page.nextCursor,
  };
}

/** 시작이나 「다시 확인」 요청의 결과를 안내 한 줄로 바꾼다. 202 는 새로 시작, 200 은 이미 도는 확인이다. */
async function startOutcome(request: Promise<Response>): Promise<Message> {
  try {
    const response = await request;
    const payload = (await response.json().catch(() => ({}))) as {
      code?: string;
      message?: string;
    };
    if (!response.ok) {
      return {
        error: true,
        text: describeAdminError(
          payload.code ?? "INTERNAL_ERROR",
          payload.message ?? "요청을 처리하지 못했어요.",
        ),
      };
    }
    return {
      error: false,
      text:
        response.status === 202
          ? "확인을 시작했어요. 기능을 차례로 돌려요."
          : "이미 확인이 돌고 있어요. 끝나면 결과가 여기에 보여요.",
    };
  } catch {
    return { error: true, text: "요청을 처리하지 못했어요." };
  }
}

/**
 * 품질 화면의 읽기와 다시 읽기다. 도는 확인이 있으면 끝날 때까지 확인 목록만 다시 읽는다.
 *
 * <p>다시 읽기가 실패해도 지금 그림을 그대로 두고 다음 차례를 건다. 한 번 실패로 줄이 「확인 중」 에 멈추지 않게 한다.
 */
function useQualityChecks() {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [pollTick, setPollTick] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void readFirst().then((next) => {
      if (cancelled) return;
      if (next === null) setFailed(true);
      else setLoaded(next);
    });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  const running =
    loaded?.checks.some((check) => check.status === "RUNNING") ?? false;
  useEffect(() => {
    if (!running) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      void readJson<QualityCheckPage>(fetchQualityChecks()).then((page) => {
        if (cancelled) return;
        if (page !== null)
          setLoaded((current) =>
            current === null ? current : merged(current, page),
          );
        setPollTick((value) => value + 1);
      });
    }, POLL_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [running, pollTick]);

  const reload = useCallback(() => {
    setFailed(false);
    setAttempt((value) => value + 1);
  }, []);

  /** 다음 쪽을 읽는다. 읽는 동안 다시 누르면 무시한다. 읽지 못하면 거짓이다. */
  async function more(cursor: string): Promise<boolean> {
    if (loadingMore) return true;
    setLoadingMore(true);
    const page = await readJson<QualityCheckPage>(fetchQualityChecks(cursor));
    setLoadingMore(false);
    if (page === null) return false;
    setLoaded((current) =>
      current === null ? current : appended(current, page),
    );
    return true;
  }

  function setEvaluation(evaluation: EvaluationUserStatus) {
    setLoaded((current) =>
      current === null ? current : { ...current, evaluation },
    );
  }

  return { loaded, failed, running, loadingMore, reload, more, setEvaluation };
}

/**
 * 관리자 「품질」 화면의 첫 칸, 배포 뒤 확인 기록이다(docs/features/agent-quality/spec.md 의 REQ4).
 *
 * <p>평가 사용자가 없으면 기록 대신 준비 칸만 보인다.
 */
export function QualityChecks() {
  const { loaded, failed, running, loadingMore, reload, more, setEvaluation } =
    useQualityChecks();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const hasUser =
    loaded !== null && !loaded.evaluation.missing.includes("USER");

  async function start(request: Promise<Response>) {
    setBusy(true);
    setMessage(null);
    setMessage(await startOutcome(request));
    setBusy(false);
    reload();
  }

  async function readMore(cursor: string) {
    if (!(await more(cursor)))
      setMessage({ error: true, text: "다음 기록을 불러오지 못했어요." });
  }

  return (
    <PageFrame
      title="품질"
      description="배포가 끝날 때마다 비서의 기능이 실제로 동작하는지 확인한 기록이에요."
      action={
        hasUser ? (
          <Button
            size="sm"
            disabled={busy || running}
            onClick={() => void start(startQualityCheck())}
          >
            확인 시작
          </Button>
        ) : undefined
      }
    >
      {failed ? (
        <LoadFailure message="불러오지 못했어요" onRetry={reload} />
      ) : loaded === null ? (
        <ListSkeleton />
      ) : (
        <div className="space-y-4">
          {loaded.evaluation.ready ? null : (
            <EvaluationUserPanel
              key={loaded.evaluation.missing.join(",")}
              status={loaded.evaluation}
              onPrepared={setEvaluation}
            />
          )}
          {hasUser ? (
            <section aria-labelledby="deploy-checks" className="space-y-3">
              <h2 id="deploy-checks" className="text-base font-semibold">
                배포 뒤 확인
              </h2>
              {message ? (
                <Notice
                  variant={message.error ? "error" : "info"}
                  role={message.error ? "alert" : "status"}
                >
                  {message.text}
                </Notice>
              ) : null}
              {loaded.checks.length === 0 ? (
                <EmptyState
                  title="아직 확인 기록이 없어요"
                  description="배포가 끝나면 파이프라인이 확인을 시작하고, 여기서도 시작할 수 있어요."
                />
              ) : (
                <ul className="space-y-2">
                  {loaded.checks.map((check) => (
                    <QualityCheckRow
                      key={check.publicId}
                      check={check}
                      busy={busy || running}
                      onRetry={(publicId) =>
                        void start(retryQualityCheck(publicId))
                      }
                    />
                  ))}
                </ul>
              )}
              {loaded.nextCursor ? (
                <Button
                  variant="outline"
                  size="sm"
                  loading={loadingMore}
                  loadingText="불러오는 중…"
                  onClick={() => void readMore(loaded.nextCursor!)}
                >
                  더 보기
                </Button>
              ) : null}
            </section>
          ) : null}
        </div>
      )}
    </PageFrame>
  );
}
