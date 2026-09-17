"use client";

import { useRouter } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { formatCost, formatDuration, formatTokens, formatWhen } from "@/lib/format";
import { executionStatusLabel, isRunning, type UsageExecution } from "./execution-list";

export function ExecutionCard({ execution }: { execution: UsageExecution }) {
  const router = useRouter();
  const failed = execution.status === "FAILED" || execution.errorCode !== null;
  const running = isRunning(execution);
  return (
    <article
      className={`cursor-pointer rounded-md border p-4 ${failed ? "border-foreground" : "border-border"}`}
      tabIndex={0}
      role="link"
      onClick={() => router.push(`/executions/${execution.id}`)}
      onKeyDown={(event) => {
        if (event.key === "Enter") router.push(`/executions/${execution.id}`);
      }}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="truncate font-semibold">
            {execution.agentName}
            {execution.hasChildren ? (
              <span className="ml-1 text-muted" title="하위 실행 있음">
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
        <span data-testid="execution-duration">{running ? "" : formatDuration(execution.latencyMs ?? 0)}</span>
        <span>{formatWhen(execution.startedAt)}</span>
        <Badge emphasis={failed}>{executionStatusLabel(execution)}</Badge>
      </div>
    </article>
  );
}
