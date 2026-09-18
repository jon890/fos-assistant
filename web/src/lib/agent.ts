/** 에이전트가 쓸 모델 한 줄. `rank` 는 1부터 세고 1이 1순위다 */
export type AgentModel = {
  rank: number;
  provider: string;
  model: string;
};

/** 지금 막혀 있는 provider 한 줄 */
export type BlockedProvider = {
  provider: string;
  blockedUntil: string;
  remainingSeconds: number;
};

export type AdminAgent = {
  id: number;
  code: string;
  name: string;
  hermesProfile: string;
  apiBaseUrl: string;
  provider: string;
  model: string;
  modelSyncedAt: string | null;
  costMode: string;
  credentialScope: string;
  visibility: "PRIVATE" | "FAMILY";
  ownerUserId: number | null;
  enabled: boolean;
  /** 이 에이전트를 묶어 둔 다중 에이전트 흐름의 이름. 없으면 null 이다 */
  flow: string | null;
  /** 이 에이전트가 쓸 모델 목록. 순위 순서다 */
  models: AgentModel[];
};

export const PRIVATE_VISIBILITY: AdminAgent["visibility"] = "PRIVATE";
export const FAMILY_VISIBILITY: AdminAgent["visibility"] = "FAMILY";

/** 막힘이 풀리기까지 남은 시간을 사람이 읽는 말로 적는다. */
export function formatRemaining(seconds: number): string {
  if (seconds < 60) return `${Math.max(0, Math.round(seconds))}초 남음`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}분 남음`;
  return `${Math.round(minutes / 60)}시간 남음`;
}
