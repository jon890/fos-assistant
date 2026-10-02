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

export async function issueServiceToken(
  body: NewServiceToken,
): Promise<MemoryApiResult<IssuedServiceToken>> {
  const result = await memoryRequest<IssuedServiceToken>(
    "/api/service-tokens",
    "토큰을 만들지 못했어요.",
    { method: "POST", body },
  );
  // 허용 목록에서 꺼진 사용자는 웹 세션이 살아 있어도 발급받지 못한다(ADR-056). 서버 원문은 영어라 따로 알린다.
  if (!result.ok && result.code === "FORBIDDEN") {
    return {
      ...result,
      message: "이 계정으로는 토큰을 만들 수 없어요. 관리자에게 문의해 주세요.",
    };
  }
  return result;
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
