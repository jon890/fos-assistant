"use client";

import { useId, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Notice } from "@/components/ui/notice";
import {
  hidesModel,
  modelKey,
  saveAgentModelDefault,
  type AgentModelDefault,
  type AgentModelSettings,
  type ModelCatalog,
} from "@/lib/model-settings";

type Props = {
  code: string;
  settings: AgentModelSettings;
  onSaved(agentDefault: AgentModelDefault): void;
};

/** 기본 모델 고르기의 「profile 값」 자리 값이다 */
const PROFILE_KEY = "";

/** reasoning 을 끄는 강도다. 「profile 값」(강도를 보내지 않음)과 다른 의도다 */
const EFFORT_NONE = "none";

/** 고른 모델이 reasoning 끄기를 받는다고 확인됐는가. 비웠으면 profile 의 기본 모델을 본다 */
function acceptsNone(catalog: ModelCatalog, key: string): boolean {
  const picked =
    key === PROFILE_KEY ? null : (JSON.parse(key) as [string, string]);
  const provider = picked?.[0] ?? catalog.defaultProvider;
  const model = picked?.[1] ?? catalog.defaultModel;
  if (model === null) return false;
  const row = catalog.providers.find((item) => item.provider === provider);
  const capability = row?.reasoning?.[model];
  // backend 의 판정과 같다. reasoning 자체를 받지 않는 모델은 끄기 지원이 참이어도 거절한다.
  return (
    capability?.disable === "SUPPORTED" && capability.support !== "UNSUPPORTED"
  );
}

/** 강도 선택지다. `none` 은 끄기를 받는 모델에서만 맨 앞에 둔다 */
function effortChoices(settings: AgentModelSettings, key: string): string[] {
  const efforts = [
    ...(acceptsNone(settings.catalog, key) ? [EFFORT_NONE] : []),
    ...settings.catalog.reasoningEfforts,
  ];
  const storedEffort = settings.agentDefault.reasoningEffort;
  // 저장된 강도가 선택지에 없어도 둔다. 없으면 모델만 바꿔 저장해도 강도가 지워진다.
  return storedEffort !== null && !efforts.includes(storedEffort)
    ? [...efforts, storedEffort]
    : efforts;
}

/** 모델을 바꾼 뒤의 강도다. 새 모델이 끄기를 받지 않으면 고른 `none` 을 「profile 값」 으로 비운다 */
function effortAfterModel(
  catalog: ModelCatalog,
  key: string,
  effort: string,
): string {
  return effort === EFFORT_NONE && !acceptsNone(catalog, key) ? "" : effort;
}

function keyOf(agentDefault: AgentModelDefault): string {
  return agentDefault.provider && agentDefault.model
    ? modelKey(agentDefault.provider, agentDefault.model)
    : PROFILE_KEY;
}

/** 대화가 모델을 고르지 않았을 때 도는 모델과 강도를 정한다. 숨긴 모델은 고를 수 없다. */
export function AgentModelDefaultForm({ code, settings, onSaved }: Props) {
  const stored = settings.agentDefault;
  const hidden = settings.hidden.entries;
  const [draftModel, setDraftModel] = useState(keyOf(stored));
  const [draftEffort, setDraftEffort] = useState(stored.reasoningEffort ?? "");
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(
    null,
  );
  const modelSelectId = useId();
  const effortSelectId = useId();

  const storedKey = keyOf(stored);
  const listed = settings.catalog.providers.some(
    (row) =>
      row.provider === stored.provider &&
      stored.model !== null &&
      row.models.includes(stored.model),
  );
  // 저장된 기본 모델이 목록에서 빠졌어도 선택지에 둔다. 없으면 강도만 바꿔 저장해도 모델이 지워진다.
  const keptKey = storedKey !== PROFILE_KEY && !listed ? storedKey : null;
  const storedHidden =
    stored.provider !== null &&
    stored.model !== null &&
    hidesModel(hidden, stored.provider, stored.model);
  const efforts = effortChoices(settings, draftModel);
  const profileLabel = settings.catalog.defaultModel
    ? `profile 값 (${settings.catalog.defaultModel})`
    : "profile 값";

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const picked =
      draftModel === PROFILE_KEY
        ? null
        : (JSON.parse(draftModel) as [string, string]);
    setSaving(true);
    setMessage(null);
    const result = await saveAgentModelDefault(code, {
      provider: picked?.[0] ?? null,
      model: picked?.[1] ?? null,
      reasoningEffort: draftEffort === "" ? null : draftEffort,
    });
    setSaving(false);
    if (!result.ok) {
      setMessage({ ok: false, text: result.message });
      return;
    }
    onSaved(result.data);
    setDraftModel(keyOf(result.data));
    setDraftEffort(result.data.reasoningEffort ?? "");
    setMessage({ ok: true, text: "기본 모델을 저장했어요." });
  }

  return (
    <form onSubmit={(event) => void save(event)} className="mt-3 grid gap-3">
      <div>
        <h3 className="text-sm font-medium">기본 모델</h3>
        <p className="mt-1 text-sm text-muted-foreground">
          대화에서 모델을 고르지 않았을 때 이 모델로 답해요. profile 값을 고르면
          Hermes profile 에 적힌 모델로 답해요.
        </p>
      </div>
      {keptKey !== null && !settings.catalogMissing ? (
        <Notice variant="warning" data-testid="agent-model-default-unlisted">
          저장된 기본 모델이 지금 목록에 없어요. 이 모델로 보낸 대화는 실패할 수
          있으니 다른 모델을 골라 주세요.
        </Notice>
      ) : storedHidden ? (
        <Notice variant="warning" data-testid="agent-model-default-hidden">
          저장된 기본 모델이 숨김 목록에 있어요. 모델을 고르지 않은 대화는
          답하지 못하니 다른 모델을 골라 주세요.
        </Notice>
      ) : null}
      <div className="grid gap-1.5">
        <Label htmlFor={modelSelectId}>모델</Label>
        <NativeSelect
          id={modelSelectId}
          value={draftModel}
          onChange={(event) => {
            setMessage(null);
            const next = event.target.value;
            setDraftModel(next);
            setDraftEffort(
              effortAfterModel(settings.catalog, next, draftEffort),
            );
          }}
        >
          <option value={PROFILE_KEY}>{profileLabel}</option>
          {keptKey !== null ? (
            <option value={keptKey}>
              {settings.catalogMissing
                ? stored.model
                : `${stored.model} (목록에 없음)`}
            </option>
          ) : null}
          {settings.catalog.providers.map((row) => (
            <optgroup key={row.provider} label={row.name}>
              {row.models.map((model) => {
                const isHidden = hidesModel(hidden, row.provider, model);
                return (
                  <option
                    key={model}
                    value={modelKey(row.provider, model)}
                    disabled={isHidden}
                  >
                    {isHidden ? `${model} (숨김)` : model}
                  </option>
                );
              })}
            </optgroup>
          ))}
        </NativeSelect>
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={effortSelectId}>강도</Label>
        <NativeSelect
          id={effortSelectId}
          value={draftEffort}
          onChange={(event) => {
            setMessage(null);
            setDraftEffort(event.target.value);
          }}
        >
          <option value="">profile 값</option>
          {efforts.map((effort) => (
            <option key={effort} value={effort}>
              {effort === EFFORT_NONE ? "끄기" : effort}
            </option>
          ))}
        </NativeSelect>
      </div>
      {message ? (
        <Notice
          variant={message.ok ? "success" : "error"}
          role={message.ok ? "status" : "alert"}
        >
          {message.text}
        </Notice>
      ) : null}
      <div>
        <Button
          type="submit"
          size="sm"
          variant="outline"
          loading={saving}
          loadingText="저장하는 중"
        >
          기본 모델 저장
        </Button>
      </div>
    </form>
  );
}
