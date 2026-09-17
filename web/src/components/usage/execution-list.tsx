import { EmptyState } from "@/components/ui/empty-state";
import { ExecutionCard } from "./execution-card";
import { ExecutionTable } from "./execution-table";

export type UsageExecution = {
  id: number;
  agentCode: string;
  agentName: string;
  provider: string | null;
  model: string | null;
  costMode: string;
  status: string;
  errorCode: string | null;
  inputTokens: number | null;
  cachedInputTokens: number | null;
  outputTokens: number | null;
  latencyMs: number | null;
  estimatedCostMicros: number | null;
  costCurrency: string | null;
  pricingVersion: string | null;
  startedAt: string;
};

export function isRunning(execution: UsageExecution): boolean {
  return execution.status === "RUNNING";
}

export function executionStatusLabel(execution: UsageExecution): string {
  if (isRunning(execution)) return "도는 중";
  if (execution.errorCode === "ORPHANED") return "중간에 끊김";
  if (execution.status === "FAILED" || execution.errorCode !== null) return execution.errorCode ?? "실패";
  return "성공";
}

export function ExecutionList({ executions }: { executions: UsageExecution[] }) {
  if (executions.length === 0) {
    return <EmptyState title="아직 실행 기록이 없다" description="에이전트와 대화하면 사용량이 여기에 쌓인다." />;
  }

  return (
    <section aria-label="실행 기록">
      <ExecutionTable executions={executions} />
      <div className="grid gap-3 md:hidden" data-testid="execution-cards">
        {executions.map((execution) => <ExecutionCard key={execution.id} execution={execution} />)}
      </div>
    </section>
  );
}
