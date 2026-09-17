import { Badge } from "@/components/ui/badge";
import { formatCost, formatDuration, formatTokens, formatWhen } from "@/lib/format";
import type { UsageExecution } from "./execution-list";

export function ExecutionCard({ execution }: { execution: UsageExecution }) {
  const failed = execution.status === "FAILED" || execution.errorCode !== null;
  return (
    <article className={`rounded-md border p-4 ${failed ? "border-foreground" : "border-border"}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="truncate font-semibold">{execution.agentName}</h2>
          <p className="truncate text-xs text-muted">{execution.agentCode}</p>
        </div>
        <span className="shrink-0 text-sm font-semibold" title={execution.pricingVersion ?? undefined}>
          {formatCost(execution.estimatedCostMicros, execution.costCurrency)}
        </span>
      </div>
      <p className="mt-3 truncate text-sm text-muted">
        {execution.provider ?? "-"} / {execution.model ?? "-"}
      </p>
      <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-2 text-sm">
        <span title={`캐시 입력 ${formatTokens(execution.cachedInputTokens)}`}>
          {formatTokens(execution.inputTokens)} → {formatTokens(execution.outputTokens)}
        </span>
        <span>{formatDuration(execution.latencyMs)}</span>
        <span>{formatWhen(execution.startedAt)}</span>
        <Badge emphasis={failed}>{execution.errorCode ?? (failed ? "실패" : "성공")}</Badge>
      </div>
    </article>
  );
}
