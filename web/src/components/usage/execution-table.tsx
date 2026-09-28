"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChevronRight } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
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

export function ExecutionTable({ executions }: { executions: UsageExecution[] }) {
  const router = useRouter();
  return (
    <Table className="hidden text-left md:table" data-testid="execution-table">
      <TableHeader className="text-xs">
        <TableRow className="hover:bg-transparent">
          <TableHead>시각</TableHead>
          <TableHead>에이전트</TableHead>
          <TableHead>모델</TableHead>
          <TableHead>상태</TableHead>
          <TableHead className="text-right">입력</TableHead>
          <TableHead className="text-right">캐시</TableHead>
          <TableHead className="text-right">출력</TableHead>
          <TableHead className="text-right">문맥</TableHead>
          <TableHead className="text-right">소요</TableHead>
          <TableHead className="text-right">API 환산 비용</TableHead>
          <TableHead className="text-right">예상 추가 사용 요금</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {executions.map((execution) => {
          const running = isRunning(execution);
          const failed = execution.status === "FAILED" || execution.errorCode !== null;
          return (
            <TableRow
              key={execution.id}
              className="cursor-pointer align-top hover:bg-muted"
              onClick={() => router.push(`/executions/${execution.id}`)}
            >
              <TableCell className="py-3">{formatWhen(execution.startedAt)}</TableCell>
              <TableCell className="py-3 whitespace-normal">
                <Link
                  href={`/executions/${execution.id}`}
                  className="font-medium hover:underline"
                  aria-label={`${execution.agentName} 실행 상세 보기 (${execution.id}번)${
                    execution.hasChildren ? ", 하위 실행 있음" : ""
                  }`}
                  onClick={(event) => event.stopPropagation()}
                >
                  {execution.agentName}
                </Link>
                {execution.hasChildren ? (
                  <ChevronRight aria-hidden="true" className="ml-1 inline-block size-4 text-muted-foreground" />
                ) : null}
                <span className="block text-xs text-muted-foreground">{execution.agentCode}</span>
              </TableCell>
              <TableCell className="max-w-40 py-3">
                <span className="block truncate">{execution.model ?? "-"}</span>
                <span className="block truncate text-xs text-muted-foreground">{execution.provider ?? "-"}</span>
              </TableCell>
              <TableCell className="max-w-40 py-3 whitespace-normal">
                <Badge variant={failed ? "default" : "outline"}>{executionStatusLabel(execution)}</Badge>
                {retryOfLabel(execution) ? (
                  <span className="mt-1 block text-xs text-muted-foreground" data-testid="execution-retry-of">
                    {retryOfLabel(execution)}
                  </span>
                ) : null}
              </TableCell>
              <TableCell className="py-3 text-right tabular-nums">{formatTokens(execution.inputTokens)}</TableCell>
              <TableCell className="py-3 text-right tabular-nums">{formatTokens(execution.cachedInputTokens)}</TableCell>
              <TableCell className="py-3 text-right tabular-nums">{formatTokens(execution.outputTokens)}</TableCell>
              <TableCell
                className="py-3 text-right tabular-nums"
                data-testid="execution-context-chars"
              >
                <span className="block">{contextCharsLabel(execution)}</span>
                {contextOmittedLabel(execution) ? (
                  <span className="block text-xs text-destructive" data-testid="execution-context-omitted">
                    {contextOmittedLabel(execution)}
                  </span>
                ) : null}
              </TableCell>
              <TableCell className="py-3 text-right" data-testid="execution-duration">
                {running ? "" : formatDuration(execution.latencyMs ?? 0)}
              </TableCell>
              <TableCell
                className="py-3 text-right"
                data-testid="execution-cost"
                title={execution.pricingVersion ?? undefined}
              >
                {running ? "" : formatCost(execution.estimatedCostMicros, execution.costCurrency)}
              </TableCell>
              <TableCell className="py-3 text-right" data-testid="execution-actual-cost">
                {actualCostLabel(execution)}
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
