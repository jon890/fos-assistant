import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { BreakdownSection } from "@/components/usage/breakdown-section";
import type { Breakdown } from "@/components/usage/breakdown-table";
import {
  ExecutionList,
  type UsageExecution,
} from "@/components/usage/execution-list";
import { FingerprintSection } from "@/components/usage/fingerprint-section";
import {
  MonthlySummary,
  type MonthlyCost,
} from "@/components/usage/monthly-summary";
import { SkillUsageList } from "@/components/usage/skill-usage-list";
import { parseUsageTab, UsageTabs } from "@/components/usage/usage-tabs";
import { callControlPlane } from "@/lib/control-plane";
import type { SkillUsageRow } from "@/lib/skill";

export default async function UsagePage({
  searchParams,
}: {
  searchParams: Promise<{ tab?: string | string[] }>;
}) {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }

  const tab = parseUsageTab((await searchParams).tab);
  // 스킬 탭일 때만 호출 이력을 더 읽는다. 다른 탭은 이 조회를 하지 않는다.
  const [
    executionsResult,
    monthlyResult,
    breakdownResult,
    fingerprintResult,
    skillsResult,
  ] = await Promise.all([
    callControlPlane<UsageExecution[]>("/api/v1/usage/executions?limit=50"),
    callControlPlane<MonthlyCost>("/api/v1/usage/monthly-cost"),
    callControlPlane<Breakdown>("/api/v1/usage/breakdown?axis=agent"),
    callControlPlane<Breakdown>("/api/v1/usage/breakdown?axis=fingerprint"),
    tab === "skills"
      ? callControlPlane<SkillUsageRow[]>("/api/v1/usage/skills")
      : null,
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
        구독 경로로 실행한 항목은 추가 사용 요금이 없어요. 같은 사용량을 API
        가격으로 계산한 금액도 보여 드려요.
      </p>
      <UsageTabs current={tab} />
      {tab === "summary" ? (
        <>
          {monthly ? <MonthlySummary monthly={monthly} /> : null}
          {breakdownResult.ok ? (
            <BreakdownSection initial={breakdownResult.data} />
          ) : (
            <p className="mb-8 text-sm">{breakdownResult.message}</p>
          )}
        </>
      ) : null}
      {tab === "executions" ? <ExecutionList executions={executions} /> : null}
      {tab === "skills" && skillsResult ? (
        skillsResult.ok ? (
          <SkillUsageList rows={skillsResult.data} />
        ) : (
          <p className="text-sm">{skillsResult.message}</p>
        )
      ) : null}
      {tab === "fingerprints" && fingerprints ? (
        <FingerprintSection
          currency={fingerprints.currency}
          rows={fingerprints.rows}
        />
      ) : null}
    </div>
  );
}
