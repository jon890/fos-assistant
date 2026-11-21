"use client";

import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Notice } from "@/components/ui/notice";
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

type Message = { error: boolean; text: string };

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
 * 관리자 「품질」 화면의 첫 칸, 배포 뒤 확인 기록이다(docs/features/agent-quality/spec.md 의 REQ4).
 *
 * <p>평가 사용자가 없으면 기록 대신 준비 칸만 보인다. 도는 확인이 있으면 끝날 때까지 목록을 다시 읽는다.
 */
export function QualityChecks() {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);

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
    const timer = setTimeout(() => setAttempt((value) => value + 1), POLL_MS);
    return () => clearTimeout(timer);
  }, [running, loaded]);

  const reload = useCallback(() => {
    setFailed(false);
    setAttempt((value) => value + 1);
  }, []);

  async function start(request: Promise<Response>) {
    setBusy(true);
    setMessage(null);
    setMessage(await startOutcome(request));
    setBusy(false);
    reload();
  }

  async function more(cursor: string) {
    const page = await readJson<QualityCheckPage>(fetchQualityChecks(cursor));
    if (page === null) {
      setMessage({ error: true, text: "다음 기록을 불러오지 못했어요." });
      return;
    }
    setLoaded((current) =>
      current === null
        ? current
        : {
            ...current,
            checks: [...current.checks, ...page.items],
            nextCursor: page.nextCursor,
          },
    );
  }

  return (
    <div className="mx-auto w-full max-w-3xl space-y-4">
      <h1 className="text-xl font-semibold">품질</h1>
      {failed ? (
        <Notice variant="error" role="alert">
          <p>불러오지 못했어요</p>
          <Button variant="outline" size="sm" className="mt-2" onClick={reload}>
            다시 읽기
          </Button>
        </Notice>
      ) : loaded === null ? (
        <p className="text-sm text-muted-foreground">불러오는 중…</p>
      ) : (
        <>
          {loaded.evaluation.ready ? null : (
            <EvaluationUserPanel
              key={loaded.evaluation.missing.join(",")}
              status={loaded.evaluation}
              onPrepared={(evaluation) =>
                setLoaded((current) =>
                  current === null ? current : { ...current, evaluation },
                )
              }
            />
          )}
          {loaded.evaluation.missing.includes("USER") ? null : (
            <section aria-labelledby="deploy-checks" className="space-y-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h2 id="deploy-checks" className="text-base font-semibold">
                  배포 뒤 확인
                </h2>
                <Button
                  size="sm"
                  disabled={busy || running}
                  onClick={() => void start(startQualityCheck())}
                >
                  확인 시작
                </Button>
              </div>
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
                  onClick={() => void more(loaded.nextCursor!)}
                >
                  더 보기
                </Button>
              ) : null}
            </section>
          )}
        </>
      )}
    </div>
  );
}
