import { memoryRequest, type MemoryApiResult } from "@/lib/memory-api";

export type ServiceTokenGrant = { collection: string; allowSensitive: boolean };

export type ServiceToken = {
  id: number;
  label: string;
  createdAt: string;
  expiresAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
  collections: ServiceTokenGrant[];
};

/** 발급 응답이다. `token` 은 이 응답에만 있다. 화면의 상태에만 두고 어디에도 저장하지 않는다. */
export type IssuedServiceToken = { info: ServiceToken; token: string };

export type NewServiceToken = {
  label: string;
  expiresInDays: number;
  collections: ServiceTokenGrant[];
};

export function listServiceTokens(): Promise<MemoryApiResult<ServiceToken[]>> {
  return memoryRequest("/api/service-tokens", "토큰 목록을 읽지 못했어요.");
}

export function issueServiceToken(
  body: NewServiceToken,
): Promise<MemoryApiResult<IssuedServiceToken>> {
  return memoryRequest("/api/service-tokens", "토큰을 만들지 못했어요.", {
    method: "POST",
    body,
  });
}

export function revokeServiceToken(id: number): Promise<MemoryApiResult<null>> {
  return memoryRequest(
    `/api/service-tokens/${id}`,
    "토큰을 폐기하지 못했어요.",
    {
      method: "DELETE",
    },
  );
}
