import { describeFailure } from "@/components/error-message";

export type ModelTierCode = "FAST" | "BALANCED" | "DEEP";
export type ModelSelectionMode = "DEFAULT" | "TIER" | "CUSTOM";

export type ModelTier = {
  tier: ModelTierCode;
  label: string;
  provider: string | null;
  model: string | null;
  reasoningEffort: string | null;
};

export type ModelTiers = {
  tiers: ModelTier[];
  userDefaultTier: ModelTierCode | null;
  groupDefaultTier: ModelTierCode | null;
  admin: boolean;
};

type RequestResult<T> = { ok: true; data: T } | { ok: false; message: string };

async function request<T>(
  path: string,
  init?: RequestInit,
): Promise<RequestResult<T | null>> {
  try {
    const response = await fetch(path, { cache: "no-store", ...init });
    if (!response.ok)
      return { ok: false, message: await describeFailure(response) };
    if (response.status === 204) return { ok: true, data: null };
    return { ok: true, data: (await response.json()) as T };
  } catch {
    return { ok: false, message: "요청을 처리하지 못했어요." };
  }
}

export async function getModelTiers(
  agentCode: string,
): Promise<RequestResult<ModelTiers>> {
  const result = await request<ModelTiers>(
    `/api/chat/model-tiers?agentCode=${encodeURIComponent(agentCode)}`,
  );
  return result.ok && result.data !== null
    ? { ok: true, data: result.data }
    : {
        ok: false,
        message: result.ok ? "단계를 읽지 못했어요." : result.message,
      };
}

export type GroupModelTiers = {
  tiers: ModelTier[];
  groupDefaultTier: ModelTierCode | null;
};

/** 관리자가 고칠 그룹의 단계 정의다. 에이전트 없이 읽고, provider 를 비운 단계는 비운 채 온다. */
export async function getGroupModelTiers(): Promise<
  RequestResult<GroupModelTiers>
> {
  const result = await request<GroupModelTiers>("/api/admin/model-tiers");
  return result.ok && result.data !== null
    ? { ok: true, data: result.data }
    : {
        ok: false,
        message: result.ok ? "단계를 읽지 못했어요." : result.message,
      };
}

export async function saveDefaultTier(
  tier: ModelTierCode | null,
): Promise<RequestResult<null>> {
  return request("/api/chat/model-tiers/default", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ tier }),
  });
}

export async function saveGroupTiers(
  tiers: ModelTier[],
  defaultTier: ModelTierCode | null,
): Promise<RequestResult<null>> {
  return request("/api/chat/model-tiers/group", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ tiers, defaultTier }),
  });
}

export function saveConversationTier<T>(
  conversationId: string,
  mode: "DEFAULT" | "TIER",
  tier: ModelTierCode | null,
): Promise<RequestResult<T>> {
  return request<T>(`/api/chat/conversations/${conversationId}/model-tier`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ mode, tier }),
  }).then((result) =>
    result.ok && result.data !== null
      ? { ok: true, data: result.data }
      : {
          ok: false,
          message: result.ok ? "단계를 저장하지 못했어요." : result.message,
        },
  );
}
