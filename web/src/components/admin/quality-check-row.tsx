"use client";

import Link from "next/link";
import { ChevronRight } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatElapsed, formatWhen } from "@/lib/format";
import {
  FEATURE_LABELS,
  REASON_LABELS,
  type QualityCheck,
  type QualityFeatureResult,
} from "@/lib/quality-api";

const STATUS: Record<
  QualityCheck["status"],
  { label: string; variant: "success" | "destructive" | "info" | "warning" }
> = {
  PASSED: { label: "통과", variant: "success" },
  FAILED: { label: "실패", variant: "destructive" },
  RUNNING: { label: "확인 중", variant: "info" },
};

/** 실패했지만 그 까닭이 평가 환경(Control Plane 연결 끊김)인 확인이다. 품질 실패와 다른 색으로 보인다. */
const ENVIRONMENT_ERROR = { label: "환경 오류", variant: "warning" } as const;

const FEATURE_STATUS: Record<QualityFeatureResult["status"], string> = {
  PASSED: "통과",
  FAILED: "실패",
  TIMED_OUT: "시간 초과",
};

const TRIGGER: Record<QualityCheck["trigger"], string> = {
  PIPELINE: "배포",
  ADMIN: "다시 확인",
};

/** 기능 하나의 결과 줄이다. 실패한 기능은 남겨 둔 평가 대화의 실행 트리로 잇는다. 30일이 지나 비워진 줄은 링크가 없다. */
function FeatureLine({ result }: { result: QualityFeatureResult }) {
  const passed = result.status === "PASSED";
  return (
    <li
      data-testid="quality-feature"
      className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 py-1"
    >
      <span className="min-w-0">
        <span className="font-medium">
          {FEATURE_LABELS[result.feature] ?? result.feature}
        </span>
        <span className={passed ? "text-success" : "text-destructive"}>
          {` · ${FEATURE_STATUS[result.status]}`}
        </span>
        {result.reasonCode ? (
          <span className="block text-muted-foreground">
            {REASON_LABELS[result.reasonCode] ?? result.reasonCode}
          </span>
        ) : null}
      </span>
      <span className="flex flex-wrap items-baseline gap-2 text-muted-foreground">
        {result.executionIds.map((id) => (
          <Link
            key={id}
            href={`/admin/quality/executions/${id}`}
            prefetch={false}
            className="underline underline-offset-4"
          >
            {`실행 #${id}`}
          </Link>
        ))}
        <span className="tabular-nums">{formatElapsed(result.elapsedMs)}</span>
      </span>
    </li>
  );
}

/** 확인 한 번의 줄이다. 시각, 계기, 결과, 걸린 시간을 한 줄로 보이고 펼치면 기능별 결과를 보인다. */
export function QualityCheckRow({
  check,
  busy,
  onRetry,
}: {
  check: QualityCheck;
  busy: boolean;
  onRetry(publicId: string): void;
}) {
  const status =
    check.status === "FAILED" && check.environmentError
      ? ENVIRONMENT_ERROR
      : STATUS[check.status];
  return (
    <li data-testid="quality-check" className="rounded-md border border-border">
      <details
        className="group"
        open={check.status === "FAILED" ? true : undefined}
      >
        <summary className="flex cursor-pointer list-none flex-wrap items-center gap-x-3 gap-y-1 p-3 text-sm">
          {/* flex 로 바꾸면 기본 펼침 삼각형이 사라져 아이콘으로 열림을 보인다. */}
          <ChevronRight
            aria-hidden="true"
            className="size-4 shrink-0 text-muted-foreground transition-transform group-open:rotate-90"
          />
          <span className="tabular-nums">{formatWhen(check.startedAt)}</span>
          <span className="text-muted-foreground">
            {TRIGGER[check.trigger]}
          </span>
          <Badge variant={status.variant}>{status.label}</Badge>
          <span className="ml-auto tabular-nums text-muted-foreground">
            {check.elapsedMs === null ? "" : formatElapsed(check.elapsedMs)}
          </span>
        </summary>
        <div className="border-t border-border px-3 pb-3 text-sm">
          {check.results.length === 0 ? (
            <p className="py-2 text-muted-foreground">
              아직 끝난 기능이 없어요.
            </p>
          ) : (
            <ul className="divide-y divide-border">
              {check.results.map((result) => (
                <FeatureLine key={result.feature} result={result} />
              ))}
            </ul>
          )}
          {check.status === "RUNNING" ? null : (
            <Button
              size="sm"
              variant="outline"
              className="mt-2"
              disabled={busy}
              onClick={() => onRetry(check.publicId)}
            >
              다시 확인
            </Button>
          )}
        </div>
      </details>
    </li>
  );
}
