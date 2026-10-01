import Link from "next/link";
import { ChevronRight } from "lucide-react";
import { cn } from "cn";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import {
  agentLabel,
  formatCost,
  formatDuration,
  formatTokens,
  formatWhen,
} from "@/lib/format";
import {
  actualCostLabel,
  contextCharsLabel,
  contextOmittedLabel,
  executionStatusLabel,
  executionStatusVariant,
  isRunning,
  reasoningEffortLabel,
  retryOfLabel,
  type UsageExecution,
} from "./execution-list";

export function ExecutionCard({ execution }: { execution: UsageExecution }) {
  const failed = execution.status === "FAILED" || execution.errorCode !== null;
  const running = isRunning(execution);
  const omitted = contextOmittedLabel(execution);
  const retryOf = retryOfLabel(execution);
  return (
    // 브라우저 검사가 카드를 `article` 조상으로 찾으므로 원소 이름을 지킨다. 실패한 실행은 테두리를 destructive 색으로 올린다.
    <article>
      <Card className={cn("relative gap-0 px-4", failed && "ring-destructive")}>
        <Link
          href={`/executions/${execution.id}`}
          // Card 가 overflow-hidden 이라 바깥으로 그린 초점 테두리가 잘린다. 브라우저의 초점 테두리를 안쪽으로 들인다.
          className="absolute inset-0 rounded-md -outline-offset-2"
          aria-label={`${agentLabel(execution.agentName)} 실행 상세 보기 (${execution.id}번)${
            execution.hasChildren ? ", 하위 실행 있음" : ""
          }`}
        />
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="truncate text-base font-semibold">
              {agentLabel(execution.agentName)}
              {execution.hasChildren ? (
                <ChevronRight
                  aria-hidden="true"
                  className="pointer-events-none ml-1 inline-block size-4 text-muted-foreground"
                />
              ) : null}
            </h2>
            {execution.agentCode === null ? null : (
              <p className="truncate text-xs text-muted-foreground">
                {execution.agentCode}
              </p>
            )}
          </div>
          <span
            className="shrink-0 text-sm font-semibold"
            data-testid="execution-cost"
            title={execution.pricingVersion ?? undefined}
          >
            {running
              ? ""
              : formatCost(
                  execution.estimatedCostMicros,
                  execution.costCurrency,
                )}
          </span>
        </div>
        <p className="mt-3 truncate text-sm text-muted-foreground">
          {execution.provider ?? "-"} / {execution.model ?? "-"}
        </p>
        {/* 표처럼 모델 옆에 붙이지 않고 다음 줄에 따로 둔다. 까닭은 `reasoningEffortLabel` 에 있고, 붙이면 긴 모델 이름과 함께 잘린다. */}
        <p className="text-xs text-muted-foreground">
          effort{" "}
          <span data-testid="execution-effort">
            {reasoningEffortLabel(execution)}
          </span>
        </p>
        <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-2 text-sm">
          <span
            title={`캐시 입력 ${formatTokens(execution.cachedInputTokens)}`}
          >
            {formatTokens(execution.inputTokens)} →{" "}
            {formatTokens(execution.outputTokens)}
          </span>
          <span data-testid="execution-context-chars">
            문맥 {contextCharsLabel(execution)}
          </span>
          <span data-testid="execution-duration">
            {running ? "" : formatDuration(execution.latencyMs ?? 0)}
          </span>
          <span>{formatWhen(execution.startedAt)}</span>
          <Badge variant={executionStatusVariant(execution)}>
            {executionStatusLabel(execution)}
          </Badge>
        </div>
        <p
          className="mt-2 text-xs text-muted-foreground"
          data-testid="execution-actual-cost"
        >
          예상 추가 사용 요금 {actualCostLabel(execution)}
        </p>
        {execution.skillNames.length > 0 ? (
          <p
            className="mt-1 text-xs text-muted-foreground"
            data-testid="execution-skills"
          >
            스킬 {execution.skillNames.join(", ")}
          </p>
        ) : null}
        {retryOf ? (
          <p
            className="mt-1 text-xs text-muted-foreground"
            data-testid="execution-retry-of"
          >
            {retryOf}
          </p>
        ) : null}
        {omitted ? (
          <p
            className="mt-1 text-xs text-destructive"
            data-testid="execution-context-omitted"
          >
            {omitted}
          </p>
        ) : null}
      </Card>
    </article>
  );
}
