"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { formatCost, formatDuration, formatTokens, formatWhen } from "@/lib/format";
import {
  actualCostLabel,
  contextCharsLabel,
  executionStatusLabel,
  isRunning,
  type UsageExecution,
} from "./execution-list";

export function ExecutionTable({ executions }: { executions: UsageExecution[] }) {
  const router = useRouter();
  return (
    <table className="hidden w-full text-left text-sm md:table" data-testid="execution-table">
      <thead className="text-xs text-muted">
        <tr>
          <th className="pb-3 pr-4 font-medium">시각</th>
          <th className="pb-3 pr-4 font-medium">에이전트</th>
          <th className="pb-3 pr-4 font-medium">모델</th>
          <th className="pb-3 pr-4 font-medium">상태</th>
          <th className="pb-3 pr-4 text-right font-medium">입력</th>
          <th className="pb-3 pr-4 text-right font-medium">캐시</th>
          <th className="pb-3 pr-4 text-right font-medium">출력</th>
          <th className="pb-3 pr-4 text-right font-medium">문맥</th>
          <th className="pb-3 pr-4 text-right font-medium">소요</th>
          <th className="pb-3 pr-4 text-right font-medium">API 환산 비용</th>
          <th className="pb-3 text-right font-medium">실제 청구액</th>
        </tr>
      </thead>
      <tbody>
        {executions.map((execution) => {
          const running = isRunning(execution);
          const failed = execution.status === "FAILED" || execution.errorCode !== null;
          return (
            <tr
              key={execution.id}
              className="cursor-pointer border-t border-border align-top hover:bg-surface"
              onClick={() => router.push(`/executions/${execution.id}`)}
            >
              <td className="py-3 pr-4 whitespace-nowrap">{formatWhen(execution.startedAt)}</td>
              <td className="py-3 pr-4">
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
                  <span className="ml-1 text-muted" aria-hidden="true">
                    ▸
                  </span>
                ) : null}
                <span className="block text-xs text-muted">{execution.agentCode}</span>
              </td>
              <td className="max-w-40 py-3 pr-4">
                <span className="block truncate">{execution.model ?? "-"}</span>
                <span className="block truncate text-xs text-muted">{execution.provider ?? "-"}</span>
              </td>
              <td className="py-3 pr-4">
                <Badge emphasis={failed}>{executionStatusLabel(execution)}</Badge>
              </td>
              <td className="py-3 pr-4 text-right tabular-nums">{formatTokens(execution.inputTokens)}</td>
              <td className="py-3 pr-4 text-right tabular-nums">{formatTokens(execution.cachedInputTokens)}</td>
              <td className="py-3 pr-4 text-right tabular-nums">{formatTokens(execution.outputTokens)}</td>
              <td
                className="py-3 pr-4 text-right whitespace-nowrap tabular-nums"
                data-testid="execution-context-chars"
              >
                {contextCharsLabel(execution)}
              </td>
              <td className="py-3 pr-4 text-right whitespace-nowrap" data-testid="execution-duration">
                {running ? "" : formatDuration(execution.latencyMs ?? 0)}
              </td>
              <td
                className="py-3 pr-4 text-right whitespace-nowrap"
                data-testid="execution-cost"
                title={execution.pricingVersion ?? undefined}
              >
                {running ? "" : formatCost(execution.estimatedCostMicros, execution.costCurrency)}
              </td>
              <td className="py-3 text-right whitespace-nowrap" data-testid="execution-actual-cost">
                {actualCostLabel(execution)}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
