"use client";

import { useId, useRef, useState, type FormEvent } from "react";
import { ChevronDown } from "lucide-react";
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

/** 대화에 적힌 모델 선택이다. 셋 다 null 이면 그 profile 의 기본값으로 돈다 */
export type ModelChoice = {
  provider: string | null;
  model: string | null;
  reasoningEffort: string | null;
};

/** `GET /api/chat/model-options` 의 응답이다 */
type ModelOptions = {
  defaultProvider: string | null;
  defaultModel: string | null;
  providers: {
    provider: string;
    name: string;
    models: string[];
    /** 모델 이름을 열쇠로 한 참거짓 표다. 값이 없는 모델은 참으로 본다 */
    reasoningCapable: Record<string, boolean>;
  }[];
  reasoningEfforts: string[];
};

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

function parseModelKey(key: string): { provider: string; model: string } | null {
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
 * 그 모델이 effort 를 받는지다.
 *
 * <p>「기본」 이면 기본 모델을 본다. 목록을 읽지 못했거나 표에 값이 없으면 참으로 본다. backend 가 모르는
 * 모델을 참으로 보는 것과 맞춘다.
 */
function acceptsEffort(options: ModelOptions | null, picked: { provider: string; model: string } | null): boolean {
  if (options === null) return true;
  const provider = picked?.provider ?? options.defaultProvider;
  const model = picked?.model ?? options.defaultModel;
  if (model === null) return true;
  const row = options.providers.find((item) => item.provider === provider);
  return row?.reasoningCapable[model] ?? true;
}

function sameChoice(left: ModelChoice | null, right: ModelChoice): boolean {
  return (left?.provider ?? null) === right.provider
    && (left?.model ?? null) === right.model
    && (left?.reasoningEffort ?? null) === right.reasoningEffort;
}

/**
 * 저장한 결과다. 실패하면 단추가 이전 값으로 돌아간다.
 *
 * <p>`reported` 는 실패했지만 부르는 쪽이 이미 알렸다는 뜻이다. 빈 대화를 만들지 못하면 입력창이 알리므로
 * 이 부품은 안내를 더 띄우지 않는다. 두 안내가 겹치면 무엇이 실패했는지 읽기 어렵다.
 */
export type ModelChoiceSaveResult = "saved" | "failed" | "reported";

type Props = {
  agentCode: string;
  choice: ModelChoice | null;
  /** 고른 값을 저장한다 */
  onChange(choice: ModelChoice): Promise<ModelChoiceSaveResult>;
  disabled: boolean;
};

/** 입력창 아래에서 대화의 모델과 effort 를 고른다. 고른 값은 다음 보내기부터 쓰인다 */
export function ModelPicker({ agentCode, choice, onChange, disabled }: Props) {
  const [open, setOpen] = useState(false);
  const [optionsState, setOptionsState] = useState<OptionsState>({ status: "loading" });
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
      const response = await fetch(`/api/chat/model-options?agentCode=${encodeURIComponent(agentCode)}`,
        { cache: "no-store" });
      if (!response.ok) throw new Error("모델 목록을 읽지 못했어요.");
      const options = (await response.json()) as ModelOptions;
      if (loadVersion.current === version) setOptionsState({ status: "loaded", options });
    } catch {
      if (loadVersion.current === version) setOptionsState({ status: "failed" });
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
    setDraftModel(shown?.provider && shown.model ? modelKey(shown.provider, shown.model) : DEFAULT_KEY);
    setDraftEffort(shown?.reasoningEffort ?? "");
    void loadOptions();
  }

  const options = optionsState.status === "loaded" ? optionsState.options : null;
  const pickedModel = parseModelKey(draftModel);
  const effortEnabled = acceptsEffort(options, pickedModel);
  const knownKeys = new Set(options?.providers.flatMap((row) => row.models.map((model) => modelKey(row.provider, model))));
  // 대화에 적힌 모델이 목록에 없으면(목록에서 빠졌거나 목록을 읽지 못했으면) 그 값도 둔다. 없으면 창을 열어
  // effort 만 바꿔도 모델이 「기본」 으로 돌아간다.
  const keptKey = shown?.provider && shown.model && !knownKeys.has(modelKey(shown.provider, shown.model))
    ? modelKey(shown.provider, shown.model) : null;
  const defaultLabel = options?.defaultModel ? `기본 (${options.defaultModel})` : "기본";
  const listedEfforts = options?.reasoningEfforts ?? REASONING_EFFORTS;
  // 대화에 적힌 effort 가 목록에 없으면 그 값도 둔다. 선택지에 없으면 창은 「기본」 을 보이는데 적용하면 옛 값이 나간다.
  const efforts = shown?.reasoningEffort && !listedEfforts.includes(shown.reasoningEffort)
    ? [...listedEfforts, shown.reasoningEffort] : listedEfforts;

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
    changeOpen(false);
    if (sameChoice(choice, next)) return;
    setSaving(next);
    setFailed(false);
    const result = await onChange(next);
    setSaving(null);
    if (result === "failed") setFailed(true);
  }

  return (
    <div className="flex min-w-0 max-w-full flex-col items-start gap-1">
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
              <DialogDescription>고른 모델은 이 대화의 다음 메시지부터 쓰여요.</DialogDescription>
            </DialogHeader>
            {optionsState.status === "failed" ? (
              <p data-testid="model-options-failed" className="text-sm text-muted-foreground">
                모델 목록을 불러오지 못했어요. 기본 모델로는 계속 보낼 수 있어요.
              </p>
            ) : null}
            <div className="grid gap-2">
              <label htmlFor={modelSelectId} className="text-sm font-medium">모델</label>
              <NativeSelect
                id={modelSelectId}
                value={draftModel}
                disabled={optionsState.status === "loading"}
                onChange={(event) => setDraftModel(event.target.value)}
              >
                <option value={DEFAULT_KEY}>{defaultLabel}</option>
                {keptKey !== null ? <option value={keptKey}>{shown?.model}</option> : null}
                {options?.providers.map((row) => (
                  <optgroup key={row.provider} label={row.name}>
                    {row.models.map((model) => (
                      <option key={model} value={modelKey(row.provider, model)}>{model}</option>
                    ))}
                  </optgroup>
                ))}
              </NativeSelect>
              {optionsState.status === "loading" ? (
                <p className="text-xs text-muted-foreground">모델 목록을 불러오고 있어요.</p>
              ) : null}
            </div>
            <div className="grid gap-2">
              <label htmlFor={effortSelectId} className="text-sm font-medium">effort</label>
              <NativeSelect
                id={effortSelectId}
                value={effortEnabled ? draftEffort : ""}
                disabled={!effortEnabled}
                onChange={(event) => setDraftEffort(event.target.value)}
              >
                <option value="">기본</option>
                {efforts.map((effort) => <option key={effort} value={effort}>{effort}</option>)}
              </NativeSelect>
              {effortEnabled ? null : (
                <p className="text-xs text-muted-foreground">이 모델은 effort 를 고를 수 없어요.</p>
              )}
            </div>
            <DialogFooter>
              <Button type="submit" disabled={optionsState.status === "loading"}>적용</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
      {failed ? (
        <p role="alert" data-testid="model-picker-error" className="px-2 text-xs text-destructive">
          모델을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.
        </p>
      ) : null}
    </div>
  );
}
