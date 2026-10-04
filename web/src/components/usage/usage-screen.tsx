import { BreakdownSection } from "@/components/usage/breakdown-section";
import type { Breakdown } from "@/components/usage/breakdown-table";
import {
  ExecutionList,
  type UsageExecution,
} from "@/components/usage/execution-list";
import { FingerprintSection } from "@/components/usage/fingerprint-section";
import {
  LatencySection,
  type LatencySummary,
} from "@/components/usage/latency-section";
import {
  MonthlySummary,
  type MonthlyCost,
} from "@/components/usage/monthly-summary";
import { SkillUsageList } from "@/components/usage/skill-usage-list";
import { parseUsageTab, UsageTabs } from "@/components/usage/usage-tabs";
import { callControlPlane } from "@/lib/control-plane";
import type { SkillUsageRow } from "@/lib/skill";

/**
 * 사용량 화면이다. 금액, 모델, 토큰 같은 내부 값은 `admin` 일 때만 읽고 그린다.
 *
 * <p>관리자 영역(`/admin/usage`)만 `admin` 을 참으로 넘긴다. `basePath` 는 탭 링크의 바탕 경로다.
 */
export async function UsageScreen({
  admin: isAdmin,
  basePath,
  searchParams,
}: {
  admin: boolean;
  basePath: string;
  searchParams: Promise<{ tab?: string | string[] }>;
}) {
  const tab = parseUsageTab((await searchParams).tab, isAdmin);
  // 스킬 탭일 때만 호출 이력을 더 읽는다. 다른 탭은 이 조회를 하지 않는다.
  // 나눠 보기 두 조회와 첫 반응 시간은 관리자에게만 그리므로 관리자일 때만 읽는다.
  const [
    executionsResult,
    monthlyResult,
    breakdownResult,
    fingerprintResult,
    latencyResult,
    skillsResult,
  ] = await Promise.all([
    callControlPlane<UsageExecution[]>("/api/v1/usage/executions?limit=50"),
    callControlPlane<MonthlyCost>("/api/v1/usage/monthly-cost"),
    isAdmin
      ? callControlPlane<Breakdown>("/api/v1/usage/breakdown?axis=agent")
      : null,
    isAdmin
      ? callControlPlane<Breakdown>("/api/v1/usage/breakdown?axis=fingerprint")
      : null,
    isAdmin
      ? callControlPlane<LatencySummary>("/api/v1/admin/usage/latency?days=30")
      : null,
    tab === "skills"
      ? callControlPlane<SkillUsageRow[]>("/api/v1/usage/skills")
      : null,
  ]);
  if (!executionsResult.ok) {
    return <p className="text-sm">{executionsResult.message}</p>;
  }

  const executions = executionsResult.data;
  const monthly = monthlyResult.ok ? monthlyResult.data : null;
  const fingerprints = fingerprintResult?.ok ? fingerprintResult.data : null;
  return (
    <div className="mx-auto w-full max-w-5xl">
      <h1 className="mb-2 text-xl font-semibold">
        {isAdmin ? "사용량과 비용" : "사용량"}
      </h1>
      <p className="mb-6 max-w-2xl text-sm leading-6 text-muted-foreground">
        {isAdmin
          ? "구독 경로로 실행한 항목은 추가 사용 요금이 없어요. 같은 사용량을 API 가격으로 계산한 금액도 보여 드려요."
          : "이번 달에 비서와 한 일을 모아 보여 드려요."}
      </p>
      <UsageTabs current={tab} isAdmin={isAdmin} basePath={basePath} />
      {tab === "summary" ? (
        <>
          {monthly ? (
            <MonthlySummary monthly={monthly} isAdmin={isAdmin} />
          ) : null}
          {breakdownResult ? (
            breakdownResult.ok ? (
              <BreakdownSection initial={breakdownResult.data} />
            ) : (
              <p className="mb-8 text-sm">{breakdownResult.message}</p>
            )
          ) : null}
          {latencyResult ? (
            latencyResult.ok ? (
              <LatencySection summary={latencyResult.data} />
            ) : (
              <p className="mb-8 text-sm">{latencyResult.message}</p>
            )
          ) : null}
        </>
      ) : null}
      {tab === "executions" ? (
        <ExecutionList executions={executions} isAdmin={isAdmin} />
      ) : null}
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
