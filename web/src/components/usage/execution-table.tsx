import { Badge } from "@/components/ui/badge";
import { formatCost, formatDuration, formatTokens, formatWhen } from "@/lib/format";
import type { UsageExecution } from "./execution-list";

export function ExecutionTable({ executions }: { executions: UsageExecution[] }) {
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
          <th className="pb-3 pr-4 text-right font-medium">소요</th>
          <th className="pb-3 text-right font-medium">API 환산 비용</th>
        </tr>
      </thead>
      <tbody>
        {executions.map((execution) => {
          const failed = execution.status === "FAILED" || execution.errorCode !== null;
          return (
            <tr key={execution.id} className="border-t border-border align-top">
              <td className="py-3 pr-4 whitespace-nowrap">{formatWhen(execution.startedAt)}</td>
              <td className="py-3 pr-4">
                <span className="font-medium">{execution.agentName}</span>
                <span className="block text-xs text-muted">{execution.agentCode}</span>
              </td>
              <td className="max-w-40 py-3 pr-4">
                <span className="block truncate">{execution.model ?? "-"}</span>
                <span className="block truncate text-xs text-muted">{execution.provider ?? "-"}</span>
              </td>
              <td className="py-3 pr-4">
                <Badge emphasis={failed}>{execution.errorCode ?? (failed ? "실패" : "성공")}</Badge>
              </td>
              <td className="py-3 pr-4 text-right tabular-nums">{formatTokens(execution.inputTokens)}</td>
              <td className="py-3 pr-4 text-right tabular-nums">{formatTokens(execution.cachedInputTokens)}</td>
              <td className="py-3 pr-4 text-right tabular-nums">{formatTokens(execution.outputTokens)}</td>
              <td className="py-3 pr-4 text-right whitespace-nowrap">{formatDuration(execution.latencyMs)}</td>
              <td className="py-3 text-right whitespace-nowrap" title={execution.pricingVersion ?? undefined}>
                {formatCost(execution.estimatedCostMicros, execution.costCurrency)}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
