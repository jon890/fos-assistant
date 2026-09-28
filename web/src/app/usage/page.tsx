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
      <p className="mb-6 max-w-2xl text-sm leading-6 text-muted-foreground">
        구독 경로로 실행한 항목은 추가 사용 요금이 없어요. 같은 사용량을 API 가격으로 계산한 금액도 보여 드려요.
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
