import { describeFailure } from "@/components/error-message";

/** ChatGPT 의 Thinking 슬라이더 칸이다. 이 차례로 그린다(ADR-20261101 / model-setting). */
export type ModelTierCode =
  "INSTANT" | "MEDIUM" | "HIGH" | "EXTRA_HIGH" | "PRO";

/** 칸 이름은 옮기지 않고 ChatGPT 의 이름을 그대로 쓴다. */
export const MODEL_TIER_LABELS: Record<ModelTierCode, string> = {
  INSTANT: "Instant",
  MEDIUM: "Medium",
  HIGH: "High",
  EXTRA_HIGH: "Extra High",
  PRO: "Pro",
};

/** 지난 단계 이름이다. 실행 기록과 사용량은 그때 고른 이름을 그대로 갖는다. */
const LEGACY_TIER_LABELS: Record<string, string> = {
  FAST: "빠르게",
  BALANCED: "균형",
  DEEP: "깊게",
};

/** 실행 기록에 남은 단계 이름을 화면 이름으로 바꾼다. 모르는 이름은 그대로 보인다. */
export function recordedTierLabel(tier: string): string {
  return (
    MODEL_TIER_LABELS[tier as ModelTierCode] ?? LEGACY_TIER_LABELS[tier] ?? tier
  );
}
export type ModelSelectionMode = "TIER" | "CUSTOM";

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
  mode: "TIER",
  tier: ModelTierCode,
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
