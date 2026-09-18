import { EmptyState } from "@/components/ui/empty-state";
import { formatCost } from "@/lib/format";
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
  contextChars: number | null;
  contextOmittedItems: number | null;
  estimatedCostMicros: number | null;
  actualCostMicros: number | null;
  costCurrency: string | null;
  pricingVersion: string | null;
  startedAt: string;
  hasChildren: boolean;
};

/** 같은 질문인데 문맥이 커진 실행을 눈으로 찾을 수 있게 글자 수를 적는다. */
export function contextCharsLabel(execution: UsageExecution): string {
  return execution.contextChars === null ? "-" : `${execution.contextChars.toLocaleString("ko-KR")}자`;
}

/**
 * 자리가 없어 그 실행의 문맥에서 빠진 Memory 항목이 있으면 그 수를 적는다.
 *
 * <p>빠진 것이 없거나 값을 모르는 지난 기록은 빈 문자열이다. 이 줄이 없으면 문맥 글자 수만 보고
 * 항목을 지운 실행과 구분할 수 없다.
 */
export function contextOmittedLabel(execution: UsageExecution): string {
  const omitted = execution.contextOmittedItems;
  return omitted === null || omitted <= 0 ? "" : `기억 ${omitted.toLocaleString("ko-KR")}개가 길어서 빠짐`;
}

export function isRunning(execution: UsageExecution): boolean {
  return execution.status === "RUNNING";
}

/**
 * 실행 하나의 실제 청구액 칸에 적을 문구다.
 *
 * <p>끝나지 않은 실행은 빈 칸이다. 끝난 구독 경로 실행은 실제 청구액이 항상 비어 있으므로
 * 「구독」 으로 적고 금액을 쓰지 않는다. 그 밖은 실제 청구액을 그대로 보인다.
 */
export function actualCostLabel(execution: UsageExecution): string {
  if (isRunning(execution)) return "";
  if (execution.costMode === "SUBSCRIPTION") return "구독";
  return formatCost(execution.actualCostMicros, execution.costCurrency);
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
