import { describeFailure } from "@/components/error-message";

export type ModelTierCode = "FAST" | "BALANCED" | "DEEP";
export type ModelSelectionMode = "DEFAULT" | "TIER" | "CUSTOM";

export type ModelTier = {
  tier: ModelTierCode;
  label: string;
  provider: string | null;
  model: string;
  reasoningEffort: string;
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
