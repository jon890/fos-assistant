/** 관리자 요약의 「품질과 실패」 카드가 읽는 그룹 전체의 최근 실패다. */
export type OverviewFailures = {
  since: string;
  total: number;
  recent: OverviewFailureLine[];
};

/** 실패 한 줄이다. `mine` 인 줄만 실행 상세로 잇는다. */
export type OverviewFailureLine = {
  id: number;
  userName: string | null;
  agentName: string | null;
  startedAt: string;
  errorCode: string | null;
  mine: boolean;
};

/** 관리자 요약의 「이번 달 사용량」 카드가 읽는 그룹 전체의 이번 달 합계다. */
export type OverviewMonthly = {
  month: string;
  currency: string;
  totalExecutions: number;
  subscriptionExecutions: number;
  actualCostMicros: number;
  /** 금액을 확인하지 못한 실행이나 도우미가 있어 금액이 실제보다 적을 수 있다 */
  incomplete: boolean;
};

/** 읽는다. 읽지 못하면 `null` 이다. 실패 본문은 화면에 그리지 않으므로 읽지 않는다. */
async function read<T>(path: string): Promise<T | null> {
  try {
    const response = await fetch(path, { cache: "no-store" });
    return response.ok ? ((await response.json()) as T) : null;
  } catch {
    return null;
  }
}

export function readOverviewFailures(): Promise<OverviewFailures | null> {
  return read<OverviewFailures>("/api/admin/overview/failures");
}

export function readOverviewMonthly(): Promise<OverviewMonthly | null> {
  return read<OverviewMonthly>("/api/admin/overview/monthly");
}
