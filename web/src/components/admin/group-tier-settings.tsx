"use client";

import { useEffect, useId, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
import { Notice } from "@/components/ui/notice";
import {
  modelKey,
  getGroupModelCatalog,
  type GroupModelCatalog,
} from "@/lib/model-settings";
import { providerLabel } from "@/lib/provider-label";
import {
  getGroupModelTiers,
  saveGroupTiers,
  type ModelTier,
  type ModelTierCode,
} from "@/lib/model-tiers";

type Load =
  | { status: "loading" }
  | { status: "failed"; message: string }
  | { status: "loaded"; catalog: GroupModelCatalog | null };

/** 목록을 읽지 못했을 때 쓰는 강도 선택지다. Control Plane 이 받는 값이라 목록 없이도 고를 수 있다 */
const FALLBACK_EFFORTS = ["low", "medium", "high", "xhigh", "max"];

type TierPatch = Partial<
  Pick<ModelTier, "provider" | "model" | "reasoningEffort">
>;

type TierFieldsProps = {
  item: ModelTier;
  /** 그룹 하나의 모델 목록이다. 읽지 못했으면 null 이다 */
  catalog: GroupModelCatalog | null;
  onChange(tier: ModelTierCode, patch: TierPatch): void;
};

/** 그 단계에 저장된 모델이 그룹 목록에 있는가. provider 를 비운 옛 정의는 목록과 비교할 수 없어 없다고 본다 */
function offered(catalog: GroupModelCatalog | null, item: ModelTier): boolean {
  if (catalog === null || item.provider === null || item.model === null)
    return false;
  return catalog.providers.some(
    (row) =>
      row.provider === item.provider && row.models.includes(item.model ?? ""),
  );
}

/**
 * 한 단계가 도는 모델과 강도를 그룹 목록에서 고르는 칸이다. 임의 문자열은 받지 않는다.
 *
 * <p>저장된 모델이 목록에 없거나 목록을 읽지 못했으면 저장된 값을 선택지 하나로 남긴다. 그대로 저장하거나 비울 수
 * 있고, 다른 모델은 목록에 있는 것만 고른다.
 */
function TierFields({ item, catalog, onChange }: TierFieldsProps) {
  /** 「목록에 없음」 안내의 id 다 */
  const missingHelpId = useId();
  const storedKey =
    item.model === null ? "" : modelKey(item.provider ?? "", item.model);
  const storedMissing = item.model !== null && !offered(catalog, item);
  const efforts = catalog?.reasoningEfforts ?? FALLBACK_EFFORTS;
  const effortOptions =
    item.reasoningEffort === null || efforts.includes(item.reasoningEffort)
      ? efforts
      : [...efforts, item.reasoningEffort];
  const storedProviderLabel =
    catalog?.providers.find((row) => row.provider === item.provider)?.name ??
    providerLabel(item.provider);

  function pickModel(value: string) {
    if (value === "") {
      onChange(item.tier, {
        provider: null,
        model: null,
        reasoningEffort: null,
      });
      return;
    }
    const [provider, model] = JSON.parse(value) as [string, string];
    // 저장된 옛 값을 다시 고르면 provider 를 비운 그대로 둔다.
    onChange(item.tier, {
      provider: provider === "" ? null : provider,
      model,
    });
  }

  return (
    <fieldset className="grid gap-2 rounded-md border border-border p-3">
      <legend className="px-1 text-sm font-medium">{item.label}</legend>
      <div className="grid gap-1">
        <label className="grid gap-1 text-sm">
          모델
          <NativeSelect
            value={storedKey}
            aria-describedby={
              storedMissing && catalog !== null ? missingHelpId : undefined
            }
            onChange={(event) => pickModel(event.target.value)}
          >
            <option value="">비워 두기 (에이전트 기본 모델로 돌아요)</option>
            {storedMissing ? (
              <option value={storedKey}>
                {storedProviderLabel
                  ? `${storedProviderLabel} · ${item.model}`
                  : item.model}{" "}
                (목록에 없음)
              </option>
            ) : null}
            {(catalog?.providers ?? []).map((row) => (
              <optgroup key={row.provider} label={row.name}>
                {row.models.map((model) => (
                  <option key={model} value={modelKey(row.provider, model)}>
                    {model}
                  </option>
                ))}
              </optgroup>
            ))}
          </NativeSelect>
        </label>
        {storedMissing && catalog !== null ? (
          <p id={missingHelpId} className="text-xs text-muted-foreground">
            저장된 모델이 지금 목록에 없어요. 그대로 두면 이 값으로 돌고,
            목록에서 다른 모델을 고를 수 있어요.
          </p>
        ) : null}
      </div>
      <label className="grid gap-1 text-sm">
        강도
        <NativeSelect
          value={item.reasoningEffort ?? ""}
          disabled={item.model === null && item.reasoningEffort === null}
          onChange={(event) =>
            onChange(item.tier, {
              reasoningEffort: event.target.value || null,
            })
          }
        >
          <option value="">고르지 않음</option>
          {effortOptions.map((effort) => (
            <option key={effort} value={effort}>
              {effort}
            </option>
          ))}
        </NativeSelect>
      </label>
    </fieldset>
  );
}

/**
 * 그룹의 기본 단계와 단계별 모델을 정한다. 단계 정의는 그룹의 설정이라 에이전트 없이 읽는다.
 * 에이전트로 읽으면 관리자가 대화를 시작할 수 없는 에이전트(다른 사용자의 비공개 에이전트)에서 조회가 실패한다.
 */
export function GroupTierSettings() {
  const [load, setLoad] = useState<Load>({ status: "loading" });
  const [draftTiers, setDraftTiers] = useState<ModelTier[]>([]);
  const [groupDefaultTier, setGroupDefaultTier] =
    useState<ModelTierCode | null>(null);
  const [saveFailed, setSaveFailed] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let active = true;
    // 목록 조회가 실패해도 단계 설정은 그린다. 그때는 저장된 값만 고를 수 있다.
    void Promise.all([getGroupModelTiers(), getGroupModelCatalog()]).then(
      ([result, catalog]) => {
        if (!active) return;
        if (result.ok) {
          setDraftTiers(result.data.tiers);
          setGroupDefaultTier(result.data.groupDefaultTier);
          setLoad({ status: "loaded", catalog });
        } else {
          setLoad({ status: "failed", message: result.message });
        }
      },
    );
    return () => {
      active = false;
    };
  }, []);

  const needsTierSetup = draftTiers.some((item) => item.model === null);
  const hasIncompleteTierMapping = draftTiers.some((item) => {
    const hasModel = item.model !== null;
    const hasReasoningEffort = item.reasoningEffort !== null;
    return (
      hasModel !== hasReasoningEffort || (!hasModel && item.provider !== null)
    );
  });

  function updateDraft(tierCode: ModelTierCode, patch: TierPatch) {
    setSaved(false);
    setDraftTiers((current) =>
      current.map((item) =>
        item.tier === tierCode ? { ...item, ...patch } : item,
      ),
    );
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaveFailed(false);
    setSaved(false);
    const result = await saveGroupTiers(draftTiers, groupDefaultTier);
    if (result.ok) setSaved(true);
    else setSaveFailed(true);
  }

  if (load.status === "loading") {
    return (
      <p className="mt-3 text-sm text-muted-foreground">
        단계 설정을 불러오고 있어요.
      </p>
    );
  }
  if (load.status === "failed") {
    return (
      <Notice variant="error" role="alert" className="mt-3">
        단계 설정을 불러오지 못했어요. {load.message}
      </Notice>
    );
  }

  return (
    <form onSubmit={(event) => void save(event)} className="mt-3 grid gap-4">
      <p className="text-sm text-muted-foreground">
        이 그룹 사용자가 어느 단계로 시작하는지와 각 단계가 어떤 모델로 도는지
        정해요. 저장하면 다음 실행부터 적용돼요.
      </p>
      {needsTierSetup ? (
        <p className="text-sm text-muted-foreground">
          단계별 모델을 아직 정하지 않았어요. 비워 두면 에이전트 기본 모델로
          돌아요.
        </p>
      ) : null}
      <section className="grid gap-2">
        <h3 className="text-sm font-medium">그룹 기본 단계</h3>
        <p className="text-xs text-muted-foreground">
          따로 고르지 않은 사용자는 이 단계로 돌아요. 각자 「내 기본값」 을
          정하면 그것이 먼저예요.
        </p>
        <label className="grid gap-1 text-sm">
          그룹 기본 단계
          <NativeSelect
            value={groupDefaultTier ?? ""}
            onChange={(event) => {
              setSaved(false);
              setGroupDefaultTier(
                (event.target.value || null) as ModelTierCode | null,
              );
            }}
          >
            <option value="">없음 (에이전트 기본 모델로 돌아요)</option>
            {draftTiers.map((item) => (
              <option key={item.tier} value={item.tier}>
                {item.label}
              </option>
            ))}
          </NativeSelect>
        </label>
      </section>
      <section className="grid gap-2">
        <h3 className="text-sm font-medium">단계별 모델</h3>
        <p className="text-xs text-muted-foreground">
          빠르게, 균형, 깊게를 골랐을 때 실제로 도는 모델과 강도예요. 비워 둔
          단계는 에이전트 기본 모델로 돌아요.
        </p>
        {load.catalog === null ? (
          <Notice variant="warning" role="alert">
            모델 목록을 불러오지 못했어요. 저장된 값만 보이고, 새 모델은 목록을
            다시 읽은 뒤에 고를 수 있어요.
          </Notice>
        ) : null}
        {draftTiers.map((item) => (
          <TierFields
            key={item.tier}
            item={item}
            catalog={load.catalog}
            onChange={updateDraft}
          />
        ))}
      </section>
      {hasIncompleteTierMapping ? (
        <p role="alert" className="text-sm text-destructive">
          모델과 강도는 함께 골라 주세요.
        </p>
      ) : null}
      {saveFailed ? (
        <p role="alert" className="text-sm text-destructive">
          그룹 모델 설정을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.
        </p>
      ) : null}
      {saved ? <Notice variant="success">저장했어요</Notice> : null}
      <div>
        <Button type="submit" disabled={hasIncompleteTierMapping}>
          저장
        </Button>
      </div>
    </form>
  );
}
