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
};

export const PRIVATE_VISIBILITY: AdminAgent["visibility"] = "PRIVATE";
export const FAMILY_VISIBILITY: AdminAgent["visibility"] = "FAMILY";
