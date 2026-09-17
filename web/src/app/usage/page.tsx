import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { BreakdownSection } from "@/components/usage/breakdown-section";
import type { Breakdown } from "@/components/usage/breakdown-table";
import { ExecutionList, type UsageExecution } from "@/components/usage/execution-list";
import { FingerprintSection } from "@/components/usage/fingerprint-section";
import { MonthlySummary, type MonthlyCost } from "@/components/usage/monthly-summary";
import { callControlPlane } from "@/lib/control-plane";

export default async function UsagePage() {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }

  const [executionsResult, monthlyResult, breakdownResult, fingerprintResult] = await Promise.all([
    callControlPlane<UsageExecution[]>("/api/v1/usage/executions?limit=50"),
    callControlPlane<MonthlyCost>("/api/v1/usage/monthly-cost"),
    callControlPlane<Breakdown>("/api/v1/usage/breakdown?axis=agent"),
    callControlPlane<Breakdown>("/api/v1/usage/breakdown?axis=fingerprint"),
  ]);
  if (!executionsResult.ok) {
    return <p className="text-sm">{executionsResult.message}</p>;
  }

  const executions = executionsResult.data;
  const monthly = monthlyResult.ok ? monthlyResult.data : null;
  const fingerprints = fingerprintResult.ok ? fingerprintResult.data : null;
  return (
    <div className="mx-auto w-full max-w-5xl">
      <h1 className="mb-2 text-xl font-semibold">사용량</h1>
      <p className="mb-6 max-w-2xl text-sm leading-6 text-muted">
        구독제로 도는 실행은 실제로 추가 청구되지 않는다. 종량 모델로 옮기면 얼마가 나갈지도 함께
        보여, 모델을 옮길지 판단할 수 있게 한다.
      </p>
      {monthly ? <MonthlySummary monthly={monthly} /> : null}
      {breakdownResult.ok
        ? <BreakdownSection initial={breakdownResult.data} />
        : <p className="mb-8 text-sm">{breakdownResult.message}</p>}
      {fingerprints
        ? <FingerprintSection currency={fingerprints.currency} rows={fingerprints.rows} />
        : null}
      <ExecutionList executions={executions} />
    </div>
  );
}
