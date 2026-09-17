import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { callControlPlane } from "@/lib/control-plane";

type Execution = {
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
  latencyMs: number;
  estimatedCostMicros: number | null;
  costCurrency: string | null;
  pricingVersion: string | null;
  startedAt: string;
};

type MonthlyCost = {
  month: string;
  currency: string;
  estimatedCostMicros: number;
  pricedExecutions: number;
  unpricedExecutions: number;
};

/** 마이크로 단위 정수를 통화 금액으로 보인다. 한 번의 실행이 1센트 아래라서 네 자리까지 적는다. */
function formatAmount(micros: number, currency: string | null): string {
  const amount = (micros / 1_000_000).toLocaleString("ko-KR", {
    minimumFractionDigits: 4,
    maximumFractionDigits: 4,
  });
  return `${amount} ${currency ?? "USD"}`;
}

function formatCost(execution: Execution): string {
  if (execution.estimatedCostMicros === null) return "가격 없음";
  return formatAmount(execution.estimatedCostMicros, execution.costCurrency);
}

export default async function UsagePage() {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }

  const [executionsResult, monthlyResult] = await Promise.all([
    callControlPlane<Execution[]>("/api/v1/usage/executions?limit=50"),
    callControlPlane<MonthlyCost>("/api/v1/usage/monthly-cost"),
  ]);
  if (!executionsResult.ok) {
    return <p className="text-sm">{executionsResult.message}</p>;
  }

  const executions = executionsResult.data;
  const monthly = monthlyResult.ok ? monthlyResult.data : null;
  return (
    <>
      <h1 className="mb-2 text-lg font-semibold">사용량</h1>
      <p className="mb-4 text-sm" style={{ color: "var(--muted)" }}>
        비용은 실제 청구액이 아니다. 구독제로 돌고 있어서, 같은 사용량을 해당 모델의 API 가격으로 환산한
        금액이다.
      </p>
      {monthly ? (
        <p className="mb-4 text-sm">
          <span style={{ color: "var(--muted)" }}>{monthly.month} 환산 합계 </span>
          <span className="font-semibold">
            {formatAmount(monthly.estimatedCostMicros, monthly.currency)}
          </span>
          {monthly.unpricedExecutions > 0 ? (
            <span style={{ color: "var(--muted)" }}>
              {" "}
              (가격을 찾지 못한 실행 {monthly.unpricedExecutions}건은 빠졌다)
            </span>
          ) : null}
        </p>
      ) : null}
      {executions.length === 0 ? (
        <p className="text-sm" style={{ color: "var(--muted)" }}>
          아직 실행 기록이 없다.
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead style={{ color: "var(--muted)" }}>
              <tr>
                <th className="py-2 pr-4">시각</th>
                <th className="py-2 pr-4">에이전트</th>
                <th className="py-2 pr-4">모델</th>
                <th className="py-2 pr-4">상태</th>
                <th className="py-2 pr-4">입력</th>
                <th className="py-2 pr-4">캐시</th>
                <th className="py-2 pr-4">출력</th>
                <th className="py-2 pr-4">소요</th>
                <th className="py-2">API 환산 비용</th>
              </tr>
            </thead>
            <tbody>
              {executions.map((execution) => (
                <tr key={execution.id} className="border-t" style={{ borderColor: "var(--border)" }}>
                  <td className="py-2 pr-4">{new Date(execution.startedAt).toLocaleString("ko-KR")}</td>
                  <td className="py-2 pr-4">{execution.agentName} ({execution.agentCode})</td>
                  <td className="py-2 pr-4">
                    {execution.provider ?? "-"} / {execution.model ?? "-"}
                  </td>
                  <td className="py-2 pr-4">{execution.errorCode ?? execution.status}</td>
                  <td className="py-2 pr-4">{execution.inputTokens ?? "-"}</td>
                  <td className="py-2 pr-4">{execution.cachedInputTokens ?? "-"}</td>
                  <td className="py-2 pr-4">{execution.outputTokens ?? "-"}</td>
                  <td className="py-2 pr-4">{execution.latencyMs} ms</td>
                  <td className="py-2" title={execution.pricingVersion ?? undefined}>
                    {formatCost(execution)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
