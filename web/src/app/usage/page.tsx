import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ExecutionList, type UsageExecution } from "@/components/usage/execution-list";
import { MonthlySummary, type MonthlyCost } from "@/components/usage/monthly-summary";
import { callControlPlane } from "@/lib/control-plane";

export default async function UsagePage() {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }

  const [executionsResult, monthlyResult] = await Promise.all([
    callControlPlane<UsageExecution[]>("/api/v1/usage/executions?limit=50"),
    callControlPlane<MonthlyCost>("/api/v1/usage/monthly-cost"),
  ]);
  if (!executionsResult.ok) {
    return <p className="text-sm">{executionsResult.message}</p>;
  }

  const executions = executionsResult.data;
  const monthly = monthlyResult.ok ? monthlyResult.data : null;
  return (
    <div className="mx-auto w-full max-w-5xl">
      <h1 className="mb-2 text-xl font-semibold">사용량</h1>
      <p className="mb-6 max-w-2xl text-sm leading-6 text-muted">
        비용은 실제 청구액이 아니다. 구독제로 돌고 있어서, 같은 사용량을 해당 모델의 API 가격으로 환산한
        금액이다.
      </p>
      {monthly ? <MonthlySummary monthly={monthly} /> : null}
      <ExecutionList executions={executions} />
    </div>
  );
}
