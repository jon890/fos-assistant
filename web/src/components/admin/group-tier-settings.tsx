"use client";

import { useEffect, useId, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { Notice } from "@/components/ui/notice";
import {
  getModelTiers,
  saveGroupTiers,
  type ModelTier,
  type ModelTierCode,
} from "@/lib/model-tiers";

type Props = { agentCode: string };

type Load =
  | { status: "loading" }
  | { status: "failed"; message: string }
  | { status: "loaded" };

type TierFieldsProps = {
  item: ModelTier;
  onChange(
    tier: ModelTierCode,
    field: "provider" | "model" | "reasoningEffort",
    value: string,
  ): void;
};

/** 한 단계가 도는 모델 제공사와 모델과 강도를 받는 입력칸이다. */
function TierFields({ item, onChange }: TierFieldsProps) {
  /** 「모델 제공사」 도움말의 id 다 */
  const providerHelpId = useId();
  return (
    <fieldset className="grid gap-2 rounded-md border border-border p-3">
      <legend className="px-1 text-sm font-medium">{item.label}</legend>
      <div className="grid gap-1">
        <label className="grid gap-1 text-sm">
          모델 제공사
          <Input
            value={item.provider ?? ""}
            aria-describedby={providerHelpId}
            onChange={(event) =>
              onChange(item.tier, "provider", event.target.value)
            }
          />
        </label>
        <p id={providerHelpId} className="text-xs text-muted-foreground">
          비워 두면 에이전트의 기본 모델 제공사를 써요.
        </p>
      </div>
      <label className="grid gap-1 text-sm">
        모델
        <Input
          value={item.model ?? ""}
          onChange={(event) => onChange(item.tier, "model", event.target.value)}
        />
      </label>
      <label className="grid gap-1 text-sm">
        강도
        <Input
          value={item.reasoningEffort ?? ""}
          onChange={(event) =>
            onChange(item.tier, "reasoningEffort", event.target.value)
          }
        />
      </label>
    </fieldset>
  );
}

/**
 * 그룹의 기본 단계와 단계별 모델을 정한다. 단계 정의는 그룹에 하나뿐이라 어느 에이전트로 읽어도 같다.
 * `agentCode` 는 조회에만 쓰고, 바뀌면 부모가 `key` 로 새로 그린다.
 */
export function GroupTierSettings({ agentCode }: Props) {
  const [load, setLoad] = useState<Load>({ status: "loading" });
  const [draftTiers, setDraftTiers] = useState<ModelTier[]>([]);
  const [groupDefaultTier, setGroupDefaultTier] =
    useState<ModelTierCode | null>(null);
  const [saveFailed, setSaveFailed] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let active = true;
    void getModelTiers(agentCode).then((result) => {
      if (!active) return;
      if (result.ok) {
        setDraftTiers(result.data.tiers);
        setGroupDefaultTier(result.data.groupDefaultTier);
        setLoad({ status: "loaded" });
      } else {
        setLoad({ status: "failed", message: result.message });
      }
    });
    return () => {
      active = false;
    };
  }, [agentCode]);

  const needsTierSetup = draftTiers.some((item) => item.model === null);
  const hasIncompleteTierMapping = draftTiers.some((item) => {
    const hasModel = item.model !== null;
    const hasReasoningEffort = item.reasoningEffort !== null;
    return (
      hasModel !== hasReasoningEffort || (!hasModel && item.provider !== null)
    );
  });

  function updateDraft(
    tierCode: ModelTierCode,
    field: keyof Pick<ModelTier, "provider" | "model" | "reasoningEffort">,
    value: string,
  ) {
    setSaved(false);
    setDraftTiers((current) =>
      current.map((item) =>
        item.tier === tierCode ? { ...item, [field]: value || null } : item,
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
        {draftTiers.map((item) => (
          <TierFields key={item.tier} item={item} onChange={updateDraft} />
        ))}
      </section>
      {hasIncompleteTierMapping ? (
        <p role="alert" className="text-sm text-destructive">
          모델과 강도는 함께 입력해 주세요.
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
