import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { formatCost, formatDuration, formatTokens, formatWhen } from "@/lib/format";
import {
  actualCostLabel,
  contextCharsLabel,
  contextOmittedLabel,
  executionStatusLabel,
  isRunning,
  retryOfLabel,
  type UsageExecution,
} from "./execution-list";

export function ExecutionCard({ execution }: { execution: UsageExecution }) {
  const failed = execution.status === "FAILED" || execution.errorCode !== null;
  const running = isRunning(execution);
  const omitted = contextOmittedLabel(execution);
  const retryOf = retryOfLabel(execution);
  return (
    <article className={`relative rounded-md border p-4 ${failed ? "border-foreground" : "border-border"}`}>
      <Link
        href={`/executions/${execution.id}`}
        className="absolute inset-0"
        aria-label={`${execution.agentName} 실행 상세 보기 (${execution.id}번)${
          execution.hasChildren ? ", 하위 실행 있음" : ""
        }`}
      />
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="truncate font-semibold">
            {execution.agentName}
            {execution.hasChildren ? (
              <span className="pointer-events-none ml-1 text-muted" aria-hidden="true">
                ▸
              </span>
            ) : null}
          </h2>
          <p className="truncate text-xs text-muted">{execution.agentCode}</p>
        </div>
        <span
          className="shrink-0 text-sm font-semibold"
          data-testid="execution-cost"
          title={execution.pricingVersion ?? undefined}
        >
          {running ? "" : formatCost(execution.estimatedCostMicros, execution.costCurrency)}
        </span>
      </div>
      <p className="mt-3 truncate text-sm text-muted">
        {execution.provider ?? "-"} / {execution.model ?? "-"}
      </p>
      <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-2 text-sm">
        <span title={`캐시 입력 ${formatTokens(execution.cachedInputTokens)}`}>
          {formatTokens(execution.inputTokens)} → {formatTokens(execution.outputTokens)}
        </span>
        <span data-testid="execution-context-chars">문맥 {contextCharsLabel(execution)}</span>
        <span data-testid="execution-duration">{running ? "" : formatDuration(execution.latencyMs ?? 0)}</span>
        <span>{formatWhen(execution.startedAt)}</span>
        <Badge emphasis={failed}>{executionStatusLabel(execution)}</Badge>
      </div>
      <p className="mt-2 text-xs text-muted" data-testid="execution-actual-cost">
        실제 청구액 {actualCostLabel(execution)}
      </p>
      {retryOf ? (
        <p className="mt-1 text-xs text-muted" data-testid="execution-retry-of">
          {retryOf}
        </p>
      ) : null}
      {omitted ? (
        <p className="mt-1 text-xs text-danger" data-testid="execution-context-omitted">
          {omitted}
        </p>
      ) : null}
    </article>
  );
}
