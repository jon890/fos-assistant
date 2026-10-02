"use client";

import {
  useEffect,
  useId,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import { ChevronDown } from "lucide-react";
import { cn } from "cn";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { NativeSelect } from "@/components/ui/native-select";
import { Input } from "@/components/ui/input";
import {
  getModelTiers,
  saveDefaultTier,
  saveGroupTiers,
  type ModelSelectionMode,
  type ModelTier,
  type ModelTierCode,
  type ModelTiers,
} from "@/lib/model-tiers";

/** 대화에 적힌 모델 선택이다. 셋 다 null 이면 그 profile 의 기본값으로 돈다 */
export type ModelChoice = {
  provider: string | null;
  model: string | null;
  reasoningEffort: string | null;
};

/** `GET /api/chat/model-options` 의 응답이다 */
type ModelOptions = {
  defaultProvider: string | null;
  /** 고르지 않았을 때 도는 모델이다. 에이전트 기본값이 있으면 그 값이고 없으면 profile 의 값이다 */
  defaultModel: string | null;
  /** 기본 모델이 `providers` 에 있는가. 그룹이 숨겼거나 목록에서 빠졌으면 거짓이다 */
  defaultAvailable?: boolean;
  providers: {
    provider: string;
    name: string;
    models: string[];
    /** 모델 이름을 열쇠로 한 reasoning 지원과 끄기(`none`) 지원이다. Hermes 가 밝히지 않은 칸은 `UNKNOWN` 이다 */
    reasoning: Record<string, ReasoningCapability>;
  }[];
  reasoningEfforts: string[];
};

/** 지원 여부다. Hermes 가 밝히지 않았으면 `UNKNOWN` 이다(ADR-059) */
type Support = "SUPPORTED" | "UNSUPPORTED" | "UNKNOWN";

type ReasoningCapability = { support: Support; disable: Support };

/** 목록을 읽지 못했거나 표에 없는 모델이다. 모르는 것을 지원한다고 채우지 않는다 */
const UNKNOWN_CAPABILITY: ReasoningCapability = {
  support: "UNKNOWN",
  disable: "UNKNOWN",
};

/** reasoning 을 끄는 effort 다. 「기본」(effort 를 보내지 않음)과 다른 의도다 */
const EFFORT_NONE = "none";

type OptionsState =
  | { status: "loading" }
  | { status: "loaded"; options: ModelOptions }
  | { status: "failed" };

/** Control Plane 이 받는 effort 다. 목록을 읽지 못했을 때만 쓴다. 그때도 effort 는 고를 수 있어야 해서 화면이 따로 갖는다 */
const REASONING_EFFORTS = ["low", "medium", "high", "xhigh", "max"];

/** 모델 고르기의 「기본」 자리 값이다 */
const DEFAULT_KEY = "";

/** provider 와 모델을 한 값으로 묶는다. 모델 이름에 `/` 가 들어갈 수 있어 구분자로 잇지 않는다 */
function modelKey(provider: string, model: string): string {
  return JSON.stringify([provider, model]);
}

function parseModelKey(
  key: string,
): { provider: string; model: string } | null {
  if (key === DEFAULT_KEY) return null;
  const [provider, model] = JSON.parse(key) as [string, string];
  return { provider, model };
}

/** 단추에 보일 글자다. 대화에 적힌 값만으로 정한다. 목록은 창을 열 때 읽어 이때는 기본 모델 이름을 모른다 */
export function modelChoiceLabel(choice: ModelChoice | null): string {
  const name = choice?.model ?? "기본";
  return choice?.reasoningEffort ? `${name} · ${choice.reasoningEffort}` : name;
}

/**
 * 그 모델의 reasoning 지원과 끄기 지원이다.
 *
 * <p>「기본」 이면 기본 모델을 본다. 목록을 읽지 못했거나 표에 값이 없으면 둘 다 `UNKNOWN` 이다.
 */
function capabilityOf(
  options: ModelOptions | null,
  picked: { provider: string; model: string } | null,
): ReasoningCapability {
  if (options === null) return UNKNOWN_CAPABILITY;
  const provider = picked?.provider ?? options.defaultProvider;
  const model = picked?.model ?? options.defaultModel;
  if (model === null) return UNKNOWN_CAPABILITY;
  const row = options.providers.find((item) => item.provider === provider);
  return row?.reasoning?.[model] ?? UNKNOWN_CAPABILITY;
}

/** effort 선택지에 보이는 글자다. `none` 만 뜻을 풀어 보인다 */
function effortLabel(effort: string): string {
  return effort === EFFORT_NONE ? "끄기" : effort;
}

function sameChoice(left: ModelChoice | null, right: ModelChoice): boolean {
  return (
    (left?.provider ?? null) === right.provider &&
    (left?.model ?? null) === right.model &&
    (left?.reasoningEffort ?? null) === right.reasoningEffort
  );
}

/**
 * 저장한 결과다. 실패하면 단추가 이전 값으로 돌아간다.
 *
 * <p>`reported` 는 실패했지만 부르는 쪽이 이미 알렸다는 뜻이다. 빈 대화를 만들지 못하면 입력창이 알리므로
 * 이 부품은 안내를 더 띄우지 않는다. 두 안내가 겹치면 무엇이 실패했는지 읽기 어렵다.
 */
export type ModelChoiceSaveResult = "saved" | "failed" | "reported";

export type ModelTierSaveResult = ModelChoiceSaveResult;

type Props = {
  agentCode: string;
  choice: ModelChoice | null;
  /** 고른 값을 저장한다 */
  onChange(choice: ModelChoice): Promise<ModelChoiceSaveResult>;
  disabled: boolean;
  inSettings?: boolean;
};

/** 입력창 아래에서 대화의 모델과 effort 를 고른다. 고른 값은 다음 보내기부터 쓰인다 */
export function ModelPicker({
  agentCode,
  choice,
  onChange,
  disabled,
  inSettings = false,
}: Props) {
  const [open, setOpen] = useState(false);
  const [optionsState, setOptionsState] = useState<OptionsState>({
    status: "loading",
  });
  const [draftModel, setDraftModel] = useState(DEFAULT_KEY);
  const [draftEffort, setDraftEffort] = useState("");
  /** 저장하는 동안 단추가 먼저 보이는 값이다. 저장이 끝나면 비우고 대화에 적힌 값을 다시 보인다 */
  const [saving, setSaving] = useState<ModelChoice | null>(null);
  const [failed, setFailed] = useState(false);
  /** 목록 읽기의 순번이다. 창을 닫았다 다시 열면 앞선 응답을 버린다 */
  const loadVersion = useRef(0);
  const modelSelectId = useId();
  const effortSelectId = useId();

  const shown = saving ?? choice;
  const label = modelChoiceLabel(shown);

  async function loadOptions() {
    const version = ++loadVersion.current;
    setOptionsState({ status: "loading" });
    try {
      const response = await fetch(
        `/api/chat/model-options?agentCode=${encodeURIComponent(agentCode)}`,
        { cache: "no-store" },
      );
      if (!response.ok) throw new Error("모델 목록을 읽지 못했어요.");
      const options = (await response.json()) as ModelOptions;
      if (loadVersion.current === version)
        setOptionsState({ status: "loaded", options });
    } catch {
      if (loadVersion.current === version)
        setOptionsState({ status: "failed" });
    }
  }

  function changeOpen(next: boolean) {
    setOpen(next);
    if (!next) {
      loadVersion.current += 1;
      return;
    }
    // 다시 고르러 왔으니 지난 저장 실패 안내는 거둔다.
    setFailed(false);
    setDraftModel(
      shown?.provider && shown.model
        ? modelKey(shown.provider, shown.model)
        : DEFAULT_KEY,
    );
    setDraftEffort(shown?.reasoningEffort ?? "");
    void loadOptions();
  }

  const options =
    optionsState.status === "loaded" ? optionsState.options : null;
  const pickedModel = parseModelKey(draftModel);
  const capability = capabilityOf(options, pickedModel);
  // 지원 미확인도 고르게 둔다. 막으면 고를 수 있는 것을 못 고르게 된다(ADR-059).
  const effortEnabled = capability.support !== "UNSUPPORTED";
  const effortUnknown =
    options !== null && effortEnabled && capability.support === "UNKNOWN";
  const knownKeys = new Set(
    options?.providers.flatMap((row) =>
      row.models.map((model) => modelKey(row.provider, model)),
    ),
  );
  // 대화에 적힌 모델이 목록에 없으면(목록에서 빠졌거나 목록을 읽지 못했으면) 그 값도 둔다. 없으면 창을 열어
  // effort 만 바꿔도 모델이 「기본」 으로 돌아간다.
  const keptKey =
    shown?.provider &&
    shown.model &&
    !knownKeys.has(modelKey(shown.provider, shown.model))
      ? modelKey(shown.provider, shown.model)
      : null;
  const defaultLabel = options?.defaultModel
    ? `기본 (${options.defaultModel})`
    : "기본";
  // `none` 은 끄기 지원이 확인된 모델에서만 고른다.
  const listedEfforts = [
    ...(effortEnabled && capability.disable === "SUPPORTED"
      ? [EFFORT_NONE]
      : []),
    ...(options?.reasoningEfforts ?? REASONING_EFFORTS),
  ];
  // 대화에 적힌 effort 가 목록에 없으면 그 값도 둔다. 선택지에 없으면 창은 「기본」 을 보이는데 적용하면 옛 값이 나간다.
  // `none` 을 더한 뒤의 목록으로 견주므로 저장된 `none` 이 둘로 보이지 않는다.
  const efforts =
    shown?.reasoningEffort && !listedEfforts.includes(shown.reasoningEffort)
      ? [...listedEfforts, shown.reasoningEffort]
      : listedEfforts;

  async function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // 창은 body 로 옮겨 그리지만 React 의 사건은 부품 나무를 따라 올라간다. 막지 않으면 입력창의 form 이
    // 이 제출을 받아 메시지를 보낸다.
    event.stopPropagation();
    const next: ModelChoice = {
      provider: pickedModel?.provider ?? null,
      model: pickedModel?.model ?? null,
      reasoningEffort: effortEnabled && draftEffort !== "" ? draftEffort : null,
    };
    // 바꾼 것이 없는지는 `none` 을 거르기 전의 값으로 정한다. 그래야 그대로 적용해도 저장된 `none` 이 남는다.
    const unchanged = sameChoice(choice, next);
    // `none` 을 새로 골랐는데 그 모델이 끄기를 받는다고 확인되지 않았으면 보내지 않는다. 목록을 읽지 못했으면
    // 판정할 수 없으니 그대로 둔다.
    if (
      !unchanged &&
      options !== null &&
      next.reasoningEffort === EFFORT_NONE &&
      capability.disable !== "SUPPORTED"
    ) {
      next.reasoningEffort = null;
    }
    changeOpen(false);
    if (unchanged || sameChoice(choice, next)) return;
    setSaving(next);
    setFailed(false);
    const result = await onChange(next);
    setSaving(null);
    if (result === "failed") setFailed(true);
  }

  return (
    <div className="flex min-w-0 max-w-full flex-col items-start gap-1">
      {inSettings ? null : (
        <span className="px-2 text-xs text-muted-foreground">고급</span>
      )}
      <Dialog open={open} onOpenChange={changeOpen}>
        <DialogTrigger asChild>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            data-testid="model-picker"
            aria-label={`모델 고르기, 지금 ${label}`}
            disabled={disabled || saving !== null}
            className="max-w-full min-w-0 text-xs text-muted-foreground"
          >
            <span className="truncate">{label}</span>
            <ChevronDown aria-hidden="true" />
          </Button>
        </DialogTrigger>
        <DialogContent closeLabel="모델 고르기 닫기">
          <form onSubmit={(event) => void apply(event)} className="grid gap-4">
            <DialogHeader>
              <DialogTitle>모델 고르기</DialogTitle>
              <DialogDescription>
                고른 모델은 이 대화의 다음 메시지부터 쓰여요.
              </DialogDescription>
            </DialogHeader>
            {optionsState.status === "failed" ? (
              <p
                data-testid="model-options-failed"
                className="text-sm text-muted-foreground"
              >
                모델 목록을 불러오지 못했어요. 기본 모델로는 계속 보낼 수
                있어요.
              </p>
            ) : null}
            <div className="grid gap-2">
              <label htmlFor={modelSelectId} className="text-sm font-medium">
                모델
              </label>
              <NativeSelect
                id={modelSelectId}
                value={draftModel}
                disabled={optionsState.status === "loading"}
                onChange={(event) => {
                  const nextModel = event.target.value;
                  setDraftModel(nextModel);
                  // 새 모델이 끄기를 받지 않으면 창의 임시 선택만 「기본」 으로 비운다. 저장된 값은 적용하기 전에는 그대로다.
                  if (
                    draftEffort === EFFORT_NONE &&
                    capabilityOf(options, parseModelKey(nextModel)).disable !==
                      "SUPPORTED"
                  ) {
                    setDraftEffort("");
                  }
                }}
              >
                <option value={DEFAULT_KEY}>{defaultLabel}</option>
                {keptKey !== null ? (
                  <option value={keptKey}>{shown?.model}</option>
                ) : null}
                {options?.providers.map((row) => (
                  <optgroup key={row.provider} label={row.name}>
                    {row.models.map((model) => (
                      <option key={model} value={modelKey(row.provider, model)}>
                        {model}
                      </option>
                    ))}
                  </optgroup>
                ))}
              </NativeSelect>
              {optionsState.status === "loading" ? (
                <p className="text-xs text-muted-foreground">
                  모델 목록을 불러오고 있어요.
                </p>
              ) : null}
              {draftModel === DEFAULT_KEY &&
              options?.defaultAvailable === false ? (
                <p
                  data-testid="model-default-unavailable"
                  className="text-xs text-warning"
                >
                  기본 모델을 지금 쓸 수 없어요. 다른 모델을 골라 주세요.
                </p>
              ) : null}
              {options !== null &&
              keptKey !== null &&
              draftModel === keptKey ? (
                <p
                  data-testid="model-choice-unlisted"
                  className="text-xs text-warning"
                >
                  이 대화에 적힌 모델이 목록에 없어요. 숨겼거나 없어진 모델이면
                  답하지 못하니 다른 모델을 골라 주세요.
                </p>
              ) : null}
            </div>
            <div className="grid gap-2">
              <label htmlFor={effortSelectId} className="text-sm font-medium">
                effort
              </label>
              <NativeSelect
                id={effortSelectId}
                value={effortEnabled ? draftEffort : ""}
                disabled={!effortEnabled}
                onChange={(event) => setDraftEffort(event.target.value)}
              >
                <option value="">기본</option>
                {efforts.map((effort) => (
                  <option key={effort} value={effort}>
                    {effortLabel(effort)}
                  </option>
                ))}
              </NativeSelect>
              {effortEnabled ? null : (
                <p className="text-xs text-muted-foreground">
                  이 모델은 effort 를 고를 수 없어요.
                </p>
              )}
              {effortUnknown ? (
                <p
                  data-testid="effort-support-unknown"
                  className="text-xs text-muted-foreground"
                >
                  이 모델의 effort 지원을 확인하지 못했어요. 골라도 모델이
                  무시할 수 있어요.
                </p>
              ) : null}
            </div>
            <DialogFooter>
              <Button
                type="submit"
                disabled={optionsState.status === "loading"}
              >
                적용
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
      {failed ? (
        <p
          role="alert"
          data-testid="model-picker-error"
          className="px-2 text-xs text-destructive"
        >
          모델을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.
        </p>
      ) : null}
    </div>
  );
}

type TierPickerProps = {
  agentCode: string;
  mode: ModelSelectionMode | null;
  tier: ModelTierCode | null;
  disabled: boolean;
  onChange(
    mode: "DEFAULT" | "TIER",
    tier: ModelTierCode | null,
  ): Promise<ModelTierSaveResult>;
  advancedPicker: ReactNode;
};

const FALLBACK_TIERS: ModelTier[] = [
  {
    tier: "FAST",
    label: "빠르게",
    provider: null,
    model: null,
    reasoningEffort: null,
  },
  {
    tier: "BALANCED",
    label: "균형",
    provider: null,
    model: null,
    reasoningEffort: null,
  },
  {
    tier: "DEEP",
    label: "깊게",
    provider: null,
    model: null,
    reasoningEffort: null,
  },
];

/** 입력창 가까이에서 세 단계와 기본값을 고른다. 고급 직접 선택은 설정 안에 둔다. */
export function ModelTierPicker({
  agentCode,
  mode,
  tier,
  disabled,
  onChange,
  advancedPicker,
}: TierPickerProps) {
  const [state, setState] = useState<{
    loading: boolean;
    data: ModelTiers | null;
  }>({ loading: true, data: null });
  const [saving, setSaving] = useState(false);
  const [failed, setFailed] = useState(false);
  const [defaultsOpen, setDefaultsOpen] = useState(false);
  const [groupOpen, setGroupOpen] = useState(false);
  const [ownDefaultSaveFailed, setOwnDefaultSaveFailed] = useState(false);
  const [groupSaveFailed, setGroupSaveFailed] = useState(false);
  const [draftTiers, setDraftTiers] = useState<ModelTier[]>(FALLBACK_TIERS);
  const [groupDefaultTier, setGroupDefaultTier] =
    useState<ModelTierCode | null>(null);
  /** 단계마다 「모델 제공사」 도움말의 id 를 만드는 앞머리다 */
  const providerHelpId = useId();

  useEffect(() => {
    let active = true;
    void getModelTiers(agentCode).then((result) => {
      if (!active) return;
      if (result.ok) {
        setState({ loading: false, data: result.data });
        setDraftTiers(result.data.tiers);
        setGroupDefaultTier(result.data.groupDefaultTier);
      } else {
        setState({ loading: false, data: null });
      }
    });
    return () => {
      active = false;
    };
  }, [agentCode]);

  const tiers = state.data?.tiers ?? FALLBACK_TIERS;
  const selectedTier = mode === "TIER" ? tier : null;
  // 아무것도 고르지 않은 대화는 실행할 때 내 기본 단계, 그룹 기본 단계 차례로 쓴다. 대화에는 적히지 않으므로
  // 여기서 보여 주지 않으면 기본값을 저장하고도 저장되지 않은 것처럼 보인다.
  const inheritedTier =
    mode === null
      ? (state.data?.userDefaultTier ?? state.data?.groupDefaultTier ?? null)
      : null;
  const groupDefaultTierLabel =
    tiers.find((item) => item.tier === state.data?.groupDefaultTier)?.label ??
    null;
  const needsTierSetup =
    state.data?.admin === true &&
    state.data.tiers.some((item) => item.model === null);
  const hasIncompleteTierMapping = draftTiers.some((item) => {
    const hasModel = item.model !== null;
    const hasReasoningEffort = item.reasoningEffort !== null;
    return (
      hasModel !== hasReasoningEffort || (!hasModel && item.provider !== null)
    );
  });

  async function choose(nextTier: ModelTierCode) {
    setSaving(true);
    setFailed(false);
    const result = await onChange("TIER", nextTier);
    setSaving(false);
    if (result === "failed") setFailed(true);
  }

  async function returnToProfileDefault() {
    setSaving(true);
    setFailed(false);
    const result = await onChange("DEFAULT", null);
    setSaving(false);
    if (result === "failed") setFailed(true);
  }

  async function saveOwnDefault(nextTier: ModelTierCode | null) {
    setOwnDefaultSaveFailed(false);
    const result = await saveDefaultTier(nextTier);
    if (result.ok) {
      setState((current) =>
        current.data === null
          ? current
          : {
              ...current,
              data: { ...current.data, userDefaultTier: nextTier },
            },
      );
      setDefaultsOpen(false);
    } else {
      setOwnDefaultSaveFailed(true);
    }
  }

  function updateDraft(
    tierCode: ModelTierCode,
    field: keyof Pick<ModelTier, "provider" | "model" | "reasoningEffort">,
    value: string,
  ) {
    setDraftTiers((current) =>
      current.map((item) =>
        item.tier === tierCode ? { ...item, [field]: value || null } : item,
      ),
    );
  }

  async function saveGroup() {
    setGroupSaveFailed(false);
    const result = await saveGroupTiers(draftTiers, groupDefaultTier);
    if (result.ok) {
      setState((current) =>
        current.data === null
          ? current
          : {
              ...current,
              data: { ...current.data, tiers: draftTiers, groupDefaultTier },
            },
      );
      setGroupOpen(false);
    } else {
      setGroupSaveFailed(true);
    }
  }

  return (
    <div
      className="flex min-w-0 flex-wrap items-center gap-1"
      data-testid="model-tier-picker"
    >
      {tiers.map((item) => (
        <Button
          key={item.tier}
          type="button"
          size="sm"
          variant={selectedTier === item.tier ? "secondary" : "ghost"}
          aria-pressed={selectedTier === item.tier}
          data-inherited={inheritedTier === item.tier || undefined}
          // 고른 단계는 옅은 강조 바탕으로, 고르지 않아 기본값으로 적용되는 단계는 테두리로 그린다.
          className={cn(
            "rounded-full",
            selectedTier === item.tier &&
              "bg-primary-soft text-primary-soft-foreground hover:bg-primary-soft",
            inheritedTier === item.tier && "border border-border",
          )}
          disabled={disabled || state.loading || state.data === null || saving}
          data-testid={`model-tier-${item.tier.toLowerCase()}`}
          onClick={() => void choose(item.tier)}
        >
          {item.label}
          {inheritedTier === item.tier ? (
            <>
              <span
                aria-hidden="true"
                className="text-xs text-muted-foreground"
              >
                기본
              </span>
              <span className="sr-only">, 지금 적용되는 기본 단계</span>
            </>
          ) : null}
        </Button>
      ))}
      <Dialog>
        <DialogTrigger asChild>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            disabled={disabled || saving}
            data-testid="model-tier-settings"
          >
            설정
          </Button>
        </DialogTrigger>
        <DialogContent
          closeLabel="모델 단계 설정 닫기"
          className="max-h-[calc(100vh-2rem)] overflow-y-auto sm:max-w-lg"
        >
          <DialogHeader>
            <DialogTitle>모델 단계 설정</DialogTitle>
            <DialogDescription>
              기본 단계를 고르거나 이 대화에서만 쓸 모델을 정할 수 있어요.
            </DialogDescription>
          </DialogHeader>
          {failed ? (
            <p role="alert" className="text-sm text-destructive">
              단계를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.
            </p>
          ) : null}
          {state.data === null ? (
            <p className="text-sm text-muted-foreground">
              단계를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.
            </p>
          ) : null}
          <section className="grid gap-2">
            <h3 className="text-sm font-medium">에이전트 기본값</h3>
            <p className="text-sm text-muted-foreground">
              이 대화의 단계 선택을 지우고 에이전트 기본값으로 돌아가요.
            </p>
            <Button
              type="button"
              variant="outline"
              disabled={disabled || saving}
              data-testid="model-tier-profile-default"
              onClick={() => void returnToProfileDefault()}
            >
              에이전트 기본값으로 돌아가기
            </Button>
          </section>
          <Dialog open={defaultsOpen} onOpenChange={setDefaultsOpen}>
            <DialogTrigger asChild>
              <Button
                type="button"
                variant="outline"
                disabled={state.data === null}
              >
                내 기본값
              </Button>
            </DialogTrigger>
            <DialogContent closeLabel="내 기본값 닫기">
              <DialogHeader>
                <DialogTitle>내 기본값</DialogTitle>
                <DialogDescription>
                  {groupDefaultTierLabel
                    ? `새 대화에서 먼저 쓸 단계를 고르세요. 정하지 않으면 그룹 기본 단계(${groupDefaultTierLabel})로 돌아요.`
                    : "새 대화에서 먼저 쓸 단계를 고르세요. 정하지 않으면 에이전트 기본 모델로 돌아요."}
                </DialogDescription>
              </DialogHeader>
              <div className="grid gap-2">
                {tiers.map((item) => (
                  <Button
                    key={item.tier}
                    variant="outline"
                    onClick={() => void saveOwnDefault(item.tier)}
                  >
                    {item.label}
                    {state.data?.userDefaultTier === item.tier
                      ? " · 지금 기본값"
                      : ""}
                  </Button>
                ))}
                <Button
                  variant="outline"
                  onClick={() => void saveOwnDefault(null)}
                >
                  내 기본값 지우기
                </Button>
                {ownDefaultSaveFailed ? (
                  <p role="alert" className="text-sm text-destructive">
                    내 기본값을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.
                  </p>
                ) : null}
              </div>
            </DialogContent>
          </Dialog>
          {state.data?.admin ? (
            <Dialog open={groupOpen} onOpenChange={setGroupOpen}>
              {needsTierSetup ? (
                <p className="text-sm text-muted-foreground">
                  단계별 모델을 아직 정하지 않았어요. 그룹 모델 설정에서 모델과
                  강도를 정해 주세요.
                </p>
              ) : null}
              <DialogTrigger asChild>
                <Button type="button" variant="outline">
                  그룹 모델 설정
                </Button>
              </DialogTrigger>
              <DialogContent
                closeLabel="그룹 모델 설정 닫기"
                className="max-h-[calc(100vh-2rem)] overflow-y-auto sm:max-w-lg"
              >
                <form
                  onSubmit={(event) => {
                    event.preventDefault();
                    void saveGroup();
                  }}
                  className="grid gap-4"
                >
                  <DialogHeader>
                    <DialogTitle>그룹 모델 설정</DialogTitle>
                    <DialogDescription>
                      이 그룹 사용자가 어느 단계로 시작하는지와 각 단계가 어떤
                      모델로 도는지 정해요. 저장하면 다음 실행부터 적용돼요.
                    </DialogDescription>
                  </DialogHeader>
                  {needsTierSetup ? (
                    <p className="text-sm text-muted-foreground">
                      단계별 모델을 아직 정하지 않았어요. 비워 두면 에이전트
                      기본 모델로 돌아요.
                    </p>
                  ) : null}
                  <section className="grid gap-2">
                    <h3 className="text-sm font-medium">그룹 기본 단계</h3>
                    <p className="text-xs text-muted-foreground">
                      따로 고르지 않은 사용자는 이 단계로 돌아요. 각자 「내
                      기본값」 을 정하면 그것이 먼저예요.
                    </p>
                    <label className="grid gap-1 text-sm">
                      그룹 기본 단계
                      <NativeSelect
                        value={groupDefaultTier ?? ""}
                        onChange={(event) =>
                          setGroupDefaultTier(
                            (event.target.value ||
                              null) as ModelTierCode | null,
                          )
                        }
                      >
                        <option value="">
                          없음 (에이전트 기본 모델로 돌아요)
                        </option>
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
                      빠르게, 균형, 깊게를 골랐을 때 실제로 도는 모델과
                      강도예요. 비워 둔 단계는 에이전트 기본 모델로 돌아요.
                    </p>
                    {draftTiers.map((item) => (
                      <fieldset
                        key={item.tier}
                        className="grid gap-2 rounded-md border border-border p-3"
                      >
                        <legend className="px-1 text-sm font-medium">
                          {item.label}
                        </legend>
                        <div className="grid gap-1">
                          <label className="grid gap-1 text-sm">
                            모델 제공사
                            <Input
                              value={item.provider ?? ""}
                              aria-describedby={`${providerHelpId}-${item.tier}`}
                              onChange={(event) =>
                                updateDraft(
                                  item.tier,
                                  "provider",
                                  event.target.value,
                                )
                              }
                            />
                          </label>
                          <p
                            id={`${providerHelpId}-${item.tier}`}
                            className="text-xs text-muted-foreground"
                          >
                            비워 두면 에이전트의 기본 모델 제공사를 써요.
                          </p>
                        </div>
                        <label className="grid gap-1 text-sm">
                          모델
                          <Input
                            value={item.model ?? ""}
                            onChange={(event) =>
                              updateDraft(
                                item.tier,
                                "model",
                                event.target.value,
                              )
                            }
                          />
                        </label>
                        <label className="grid gap-1 text-sm">
                          강도
                          <Input
                            value={item.reasoningEffort ?? ""}
                            onChange={(event) =>
                              updateDraft(
                                item.tier,
                                "reasoningEffort",
                                event.target.value,
                              )
                            }
                          />
                        </label>
                      </fieldset>
                    ))}
                  </section>
                  {hasIncompleteTierMapping ? (
                    <p role="alert" className="text-sm text-destructive">
                      모델과 강도는 함께 입력해 주세요.
                    </p>
                  ) : null}
                  {groupSaveFailed ? (
                    <p role="alert" className="text-sm text-destructive">
                      그룹 모델 설정을 저장하지 못했어요. 잠시 뒤 다시 시도해
                      주세요.
                    </p>
                  ) : null}
                  <DialogFooter>
                    <Button type="submit" disabled={hasIncompleteTierMapping}>
                      저장
                    </Button>
                  </DialogFooter>
                </form>
              </DialogContent>
            </Dialog>
          ) : null}
          <section className="grid gap-2">
            <h3 className="text-sm font-medium">고급 직접 선택</h3>
            {advancedPicker}
          </section>
        </DialogContent>
      </Dialog>
    </div>
  );
}
