import { describeFailure } from "@/components/error-message";

/** 숨긴 provider 또는 모델 하나다. `model` 이 null 이면 그 provider 전체다 */
export type HiddenModelEntry = { provider: string; model: string | null };

/** 에이전트에 저장된 기본 모델이다. 세 값이 모두 null 이면 profile 의 값으로 돈다 */
export type AgentModelDefault = {
  provider: string | null;
  model: string | null;
  reasoningEffort: string | null;
};

/** 숨김을 적용하지 않은 목록이다. 기본 provider 와 기본 모델은 profile 의 값이다 */
export type ModelCatalog = {
  defaultProvider: string | null;
  defaultModel: string | null;
  providers: { provider: string; name: string; models: string[] }[];
  reasoningEfforts: string[];
};

/** `GET /api/admin/agents/{code}/model-settings` 의 응답이다. 목록을 읽지 못했으면 `catalog` 가 null 이다 */
export type AgentModelSettingsResponse = {
  agentDefault: AgentModelDefault;
  catalog: ModelCatalog | null;
  hidden: { entries: HiddenModelEntry[] };
};

/** 화면이 쓰는 모양이다. 목록을 읽지 못했으면 빈 목록을 두고 `catalogMissing` 으로 알린다 */
export type AgentModelSettings = {
  agentDefault: AgentModelDefault;
  catalog: ModelCatalog;
  catalogMissing: boolean;
  hidden: { entries: HiddenModelEntry[] };
};

/** 목록을 읽지 못했을 때 쓴다. 강도는 Control Plane 이 받는 값이라 목록 없이도 고를 수 있다 */
const EMPTY_CATALOG: ModelCatalog = {
  defaultProvider: null,
  defaultModel: null,
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

export async function getAgentModelSettings(
  code: string,
): Promise<Result<AgentModelSettings>> {
  const result = await request<AgentModelSettingsResponse>(
    `/api/admin/agents/${code}/model-settings`,
  );
  if (!result.ok) return result;
  return {
    ok: true,
    data: {
      ...result.data,
      catalog: result.data.catalog ?? EMPTY_CATALOG,
      catalogMissing: result.data.catalog === null,
    },
  };
}

export function saveAgentModelDefault(
  code: string,
  choice: AgentModelDefault,
): Promise<Result<AgentModelDefault>> {
  return request(`/api/admin/agents/${code}/model-default`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(choice),
  });
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

/**
 * 에이전트 기본 모델이 없어 profile 의 기본 모델로 도는데 그 모델이 숨겨졌는가.
 *
 * Control Plane 이 실행 직전에 하는 판정과 같다. profile 의 기본 provider 를 모르면 모델 이름만 견주고,
 * 그때 provider 전체를 숨긴 항목은 견주지 않는다.
 */
export function hidesProfileDefault(settings: AgentModelSettings): boolean {
  const { defaultProvider, defaultModel } = settings.catalog;
  if (
    settings.catalogMissing ||
    settings.agentDefault.model !== null ||
    defaultModel === null
  )
    return false;
  if (defaultProvider !== null)
    return hidesModel(settings.hidden.entries, defaultProvider, defaultModel);
  return settings.hidden.entries.some((entry) => entry.model === defaultModel);
}
