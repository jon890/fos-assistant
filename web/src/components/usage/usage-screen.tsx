import { BreakdownSection } from "@/components/usage/breakdown-section";
import type { Breakdown } from "@/components/usage/breakdown-table";
import { PagedExecutionList } from "@/components/usage/paged-execution-list";
import {
  AttentionMetricsSection,
  type AttentionMetrics,
} from "@/components/usage/attention-metrics-section";
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
import type { UsageExecutionPage } from "@/lib/usage-paging";

/** 관리자 사용량 화면이다. `basePath` 는 탭 링크의 바탕 경로다. */
export async function UsageScreen({
  basePath,
  searchParams,
}: {
  basePath: string;
  searchParams: Promise<{ tab?: string | string[] }>;
}) {
  const tab = parseUsageTab((await searchParams).tab);
  // 스킬 탭일 때만 호출 이력을 더 읽는다. 다른 탭은 이 조회를 하지 않는다.
  // 첫 반응 시간은 관리자의 요약 탭에만 그리므로 그때만 읽는다.
  const [
    executionsResult,
    monthlyResult,
    breakdownResult,
    fingerprintResult,
    latencyResult,
    attentionResult,
    skillsResult,
  ] = await Promise.all([
    callControlPlane<UsageExecutionPage>(
      "/api/v1/usage/executions/page?limit=50",
    ),
    callControlPlane<MonthlyCost>("/api/v1/usage/monthly-cost"),
    callControlPlane<Breakdown>("/api/v1/usage/breakdown?axis=agent"),
    callControlPlane<Breakdown>("/api/v1/usage/breakdown?axis=fingerprint"),
    tab === "summary"
      ? callControlPlane<LatencySummary>("/api/v1/admin/usage/latency?days=30")
      : null,
    tab === "summary"
      ? callControlPlane<AttentionMetrics>(
          "/api/v1/admin/attention/metrics?days=30",
        )
      : null,
    tab === "skills"
      ? callControlPlane<SkillUsageRow[]>("/api/v1/usage/skills")
      : null,
  ]);
  if (!executionsResult.ok) {
    return <p className="text-sm">{executionsResult.message}</p>;
  }

  const executionPage = executionsResult.data;
  const monthly = monthlyResult.ok ? monthlyResult.data : null;
  const fingerprints = fingerprintResult.ok ? fingerprintResult.data : null;
  return (
    <div className="mx-auto w-full max-w-5xl">
      <h1 className="mb-2 text-xl font-semibold">사용량과 비용</h1>
      <p className="mb-6 max-w-2xl text-sm leading-6 text-muted-foreground">
        구독 경로로 실행한 항목은 추가 사용 요금이 없어요. 같은 사용량을 API
        가격으로 계산한 금액도 보여 드려요.
      </p>
      <UsageTabs current={tab} basePath={basePath} />
      {tab === "summary" ? (
        <>
          {monthly ? <MonthlySummary monthly={monthly} /> : null}
          {breakdownResult.ok ? (
            <BreakdownSection initial={breakdownResult.data} />
          ) : (
            <p className="mb-8 text-sm">{breakdownResult.message}</p>
          )}
          {latencyResult ? (
            latencyResult.ok ? (
              <LatencySection summary={latencyResult.data} />
            ) : (
              <p className="mb-8 text-sm">{latencyResult.message}</p>
            )
          ) : null}
          {attentionResult ? (
            attentionResult.ok ? (
              <AttentionMetricsSection metrics={attentionResult.data} />
            ) : (
              <p className="mb-8 text-sm">{attentionResult.message}</p>
            )
          ) : null}
        </>
      ) : null}
      {tab === "executions" ? (
        <PagedExecutionList
          key={JSON.stringify(executionPage)}
          initialPage={executionPage}
        />
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
