"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChevronRight } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { providerLabel } from "@/lib/provider-label";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { agentLabel, formatCost, formatTokens, formatWhen } from "@/lib/format";
import {
  executionStatusLabel,
  executionStatusVariant,
  isRunning,
} from "@/lib/execution-status";
import {
  actualCostLabel,
  contextCharsLabel,
  contextOmittedLabel,
  durationLabel,
  executionPath,
  reasoningEffortLabel,
  retryOfLabel,
  type UsageExecution,
} from "./execution-list";

export function ExecutionTable({
  executions,
  isAdmin,
}: {
  executions: UsageExecution[];
  isAdmin: boolean;
}) {
  const router = useRouter();
  return (
    <Table className="hidden text-left md:table" data-testid="execution-table">
      <TableHeader className="text-xs">
        <TableRow className="hover:bg-transparent">
          <TableHead>시각</TableHead>
          <TableHead>에이전트</TableHead>
          {isAdmin ? <TableHead>모델</TableHead> : null}
          <TableHead>상태</TableHead>
          {isAdmin ? (
            <>
              <TableHead className="text-right">입력</TableHead>
              <TableHead className="text-right">캐시</TableHead>
              <TableHead className="text-right">출력</TableHead>
              <TableHead className="text-right">문맥</TableHead>
            </>
          ) : null}
          <TableHead className="text-right">소요</TableHead>
          {isAdmin ? (
            <>
              <TableHead className="text-right">API 환산 비용</TableHead>
              <TableHead className="text-right">예상 추가 사용 요금</TableHead>
            </>
          ) : null}
        </TableRow>
      </TableHeader>
      <TableBody>
        {executions.map((execution) => {
          const running = isRunning(execution);
          const omitted = contextOmittedLabel(execution);
          return (
            <TableRow
              key={execution.id}
              className="cursor-pointer align-top hover:bg-muted"
              onClick={() => router.push(executionPath(execution.id, isAdmin))}
            >
              <TableCell className="py-3">
                {formatWhen(execution.startedAt)}
              </TableCell>
              <TableCell className="py-3 whitespace-normal">
                <Link
                  prefetch={false}
                  href={executionPath(execution.id, isAdmin)}
                  className="font-medium hover:underline"
                  aria-label={`${agentLabel(execution.agentName)} 실행 상세 보기 (${execution.id}번)${
                    execution.hasChildren ? ", 하위 실행 있음" : ""
                  }`}
                  onClick={(event) => event.stopPropagation()}
                >
                  {agentLabel(execution.agentName)}
                </Link>
                {execution.hasChildren ? (
                  <ChevronRight
                    aria-hidden="true"
                    className="ml-1 inline-block size-4 text-muted-foreground"
                  />
                ) : null}
                {!isAdmin || execution.agentCode === null ? null : (
                  <span className="block text-xs text-muted-foreground">
                    {execution.agentCode}
                  </span>
                )}
                {execution.skillNames.length > 0 ? (
                  <span
                    className="mt-1 block text-xs text-muted-foreground"
                    data-testid="execution-skills"
                  >
                    스킬 {execution.skillNames.join(", ")}
                  </span>
                ) : null}
              </TableCell>
              {isAdmin ? (
                <TableCell className="max-w-40 py-3">
                  <span className="block truncate">
                    {execution.model ?? "-"}
                  </span>
                  <span className="block truncate text-xs text-muted-foreground">
                    {providerLabel(execution.provider) ?? "-"}
                  </span>
                  <span
                    className="block truncate text-xs text-muted-foreground"
                    data-testid="execution-effort"
                  >
                    {reasoningEffortLabel(execution)}
                  </span>
                </TableCell>
              ) : null}
              <TableCell className="max-w-40 py-3 whitespace-normal">
                <Badge variant={executionStatusVariant(execution)}>
                  {executionStatusLabel(execution, isAdmin)}
                </Badge>
                {retryOfLabel(execution) ? (
                  <span
                    className="mt-1 block text-xs text-muted-foreground"
                    data-testid="execution-retry-of"
                  >
                    {retryOfLabel(execution)}
                  </span>
                ) : null}
              </TableCell>
              {isAdmin ? (
                <>
                  <TableCell className="py-3 text-right tabular-nums">
                    {formatTokens(execution.inputTokens)}
                  </TableCell>
                  <TableCell className="py-3 text-right tabular-nums">
                    {formatTokens(execution.cachedInputTokens)}
                  </TableCell>
                  <TableCell className="py-3 text-right tabular-nums">
                    {formatTokens(execution.outputTokens)}
                  </TableCell>
                  <TableCell
                    className="py-3 text-right tabular-nums"
                    data-testid="execution-context-chars"
                  >
                    <span className="block">
                      {contextCharsLabel(execution)}
                    </span>
                    {omitted ? (
                      <span
                        className="block text-xs text-destructive"
                        data-testid="execution-context-omitted"
                      >
                        {omitted}
                      </span>
                    ) : null}
                  </TableCell>
                </>
              ) : null}
              <TableCell
                className="py-3 text-right"
                data-testid="execution-duration"
              >
                {durationLabel(execution, isAdmin)}
              </TableCell>
              {isAdmin ? (
                <>
                  <TableCell
                    className="py-3 text-right"
                    data-testid="execution-cost"
                    title={execution.pricingVersion ?? undefined}
                  >
                    {running
                      ? ""
                      : formatCost(
                          execution.estimatedCostMicros,
                          execution.costCurrency,
                        )}
                  </TableCell>
                  <TableCell
                    className="py-3 text-right"
                    data-testid="execution-actual-cost"
                  >
                    {actualCostLabel(execution)}
                  </TableCell>
                </>
              ) : null}
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
