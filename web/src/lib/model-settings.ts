import { describeFailure } from "@/components/error-message";

/** 숨긴 provider 또는 모델 하나다. `model` 이 null 이면 그 provider 전체다 */
export type HiddenModelEntry = { provider: string; model: string | null };

/** 그룹 하나의 모델 목록이다. 단계별 모델을 고르는 목록과 숨길 모델을 고르는 후보가 같은 모양을 쓴다 */
export type GroupModelCatalog = {
  providers: {
    provider: string;
    name: string;
    models: string[];
    /** 모델 이름을 열쇠로 한 reasoning 지원과 끄기(`none`) 지원이다. 값은 `SUPPORTED`, `UNSUPPORTED`, `UNKNOWN` 이다 */
    reasoning: Record<string, { support: string; disable: string }>;
  }[];
  reasoningEfforts: string[];
};

/** 화면이 쓰는 숨김 설정이다. 후보를 읽지 못했으면 빈 목록을 두고 `catalogMissing` 으로 알린다 */
export type HiddenModelSettings = {
  catalog: GroupModelCatalog;
  catalogMissing: boolean;
  hidden: { entries: HiddenModelEntry[] };
};

/** 목록을 읽지 못했을 때 쓴다 */
const EMPTY_CATALOG: GroupModelCatalog = {
  providers: [],
  reasoningEfforts: ["low", "medium", "high", "xhigh", "max"],
};

type Result<T> = { ok: true; data: T } | { ok: false; message: string };

async function request<T>(
  path: string,
  init?: RequestInit,
): Promise<Result<T>> {
  try {
    const response = await fetch(path, { cache: "no-store", ...init });
    if (!response.ok)
      return { ok: false, message: await describeFailure(response) };
    return {
      ok: true,
      data: (response.status === 204 ? null : await response.json()) as T,
    };
  } catch {
    return { ok: false, message: "요청을 처리하지 못했어요." };
  }
}

/**
 * `GET /api/admin/model-catalog` 를 읽는다. Hermes 가 목록을 답하지 못했으면 `catalog` 가 null 이고,
 * 요청 자체가 실패해도 null 로 돌려준다. 두 경우 모두 화면은 저장된 값만 보인다.
 */
export async function getGroupModelCatalog(): Promise<GroupModelCatalog | null> {
  const result = await request<{ catalog: GroupModelCatalog | null }>(
    "/api/admin/model-catalog",
  );
  return result.ok ? (result.data?.catalog ?? null) : null;
}

/**
 * 숨김 설정과 숨길 모델의 후보를 함께 읽는다. 후보를 읽지 못했으면 `catalogMissing` 이 참이고, 그때도 저장된
 * 숨김을 풀 수 있다. 숨김 목록을 읽지 못하면 실패다.
 */
export async function getHiddenModelSettings(): Promise<
  Result<HiddenModelSettings>
> {
  const [hidden, candidates] = await Promise.all([
    request<{ entries: HiddenModelEntry[] }>("/api/admin/model-hidden"),
    request<{ catalog: GroupModelCatalog | null }>(
      "/api/admin/model-hidden/candidates",
    ),
  ]);
  if (!hidden.ok) return hidden;
  const catalog = candidates.ok ? (candidates.data?.catalog ?? null) : null;
  return {
    ok: true,
    data: {
      catalog: catalog ?? EMPTY_CATALOG,
      catalogMissing: catalog === null,
      hidden: { entries: hidden.data.entries },
    },
  };
}

export function saveHiddenModels(
  entries: HiddenModelEntry[],
): Promise<Result<null>> {
  return request("/api/admin/model-hidden", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ entries }),
  });
}

export function sameHiddenEntry(
  left: HiddenModelEntry,
  right: HiddenModelEntry,
): boolean {
  return left.provider === right.provider && left.model === right.model;
}

/** provider 와 모델을 한 값으로 묶는다. 모델 이름에 `/` 가 들어갈 수 있어 구분자로 잇지 않는다 */
export function modelKey(provider: string, model: string): string {
  return JSON.stringify([provider, model]);
}

/** 그 모델이 숨김 목록에 걸리는가. provider 전체를 숨긴 것도 포함한다 */
export function hidesModel(
  entries: HiddenModelEntry[],
  provider: string,
  model: string,
): boolean {
  return entries.some(
    (entry) =>
      entry.provider === provider &&
      (entry.model === null || entry.model === model),
  );
}
