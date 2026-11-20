"use client";

import Link from "next/link";
import { agentLabel, formatWhen } from "@/lib/format";
import {
  readOverviewFailures,
  type OverviewFailureLine,
} from "@/lib/admin-overview-api";
import { OverviewCard } from "./overview-card";
import { useOverviewRead } from "./use-overview-read";

/**
 * 사람 이름, 에이전트, 시각, 오류 코드를 한 줄로 적는다. 시각과 에이전트 대체 문구는 실행 기록 표와 같다.
 *
 * <p>오류 코드 없이 상태만 실패인 실행은 실행 기록의 배지처럼 「실패」 로 적는다.
 */
function lineText(line: OverviewFailureLine): string {
  return [
    line.userName ?? "알 수 없음",
    agentLabel(line.agentName),
    formatWhen(line.startedAt),
    line.errorCode ?? "실패",
  ].join(" · ");
}

/**
 * 「품질과 실패」 카드다. 그룹 전체의 최근 7일 실패 수와 최근 세 건을 보인다.
 *
 * <p>관리자 자신의 실행만 실행 상세로 잇는다. 남의 실행은 이름과 오류 코드만 보이고 열 길이 없다.
 * 품질 확인 결과는 에이전트 품질 스펙이 같은 카드의 아래 자리에 더한다.
 */
export function OverviewFailuresCard() {
  const [read, retry] = useOverviewRead(readOverviewFailures);
  return (
    <OverviewCard
      title="품질과 실패"
      testId="overview-failures"
      read={read}
      onRetry={retry}
      link={{ href: "/admin/usage?tab=executions", label: "내 실행 기록" }}
    >
      {(failures) => (
        <>
          <p className="flex items-baseline justify-between gap-2">
            <span className="text-muted-foreground">
              최근 7일 동안 실패한 실행
            </span>
            <span
              data-testid="overview-failure-total"
              className="text-xl font-semibold tabular-nums"
            >
              {`${failures.total.toLocaleString("ko-KR")}건`}
            </span>
          </p>
          {failures.total === 0 ? (
            <p className="text-muted-foreground">
              최근 7일 동안 실패한 실행이 없어요
            </p>
          ) : (
            <ul aria-label="최근 실패" className="flex flex-col gap-1">
              {failures.recent.map((line) => (
                <li key={line.id} data-testid="overview-failure">
                  {line.mine ? (
                    <Link
                      href={`/admin/executions/${line.id}`}
                      className="block rounded-md px-2 py-1 hover:bg-muted"
                    >
                      {lineText(line)}
                    </Link>
                  ) : (
                    <span className="block px-2 py-1">{lineText(line)}</span>
                  )}
                </li>
              ))}
            </ul>
          )}
          <p className="text-muted-foreground">아직 품질 확인 결과가 없어요</p>
        </>
      )}
    </OverviewCard>
  );
}
