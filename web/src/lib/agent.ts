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
};

export const PRIVATE_VISIBILITY: AdminAgent["visibility"] = "PRIVATE";
export const FAMILY_VISIBILITY: AdminAgent["visibility"] = "FAMILY";
