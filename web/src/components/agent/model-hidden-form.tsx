"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import {
  modelKey,
  sameHiddenEntry,
  saveHiddenModels,
  type AgentModelSettings,
  type HiddenModelEntry,
} from "@/lib/model-settings";
import { providerLabel } from "@/lib/provider-label";

type Props = {
  settings: AgentModelSettings;
  onSaved(entries: HiddenModelEntry[]): void;
};

/** 한 제공사의 모델이 이보다 많으면 숨김 목록을 접어 둔다 */
const MODELS_OPEN_LIMIT = 10;

type ProviderRow = AgentModelSettings["catalog"]["providers"][number];

/** 모델 제공사 하나의 숨김 고르기다. 전체를 숨기면 모델별 고르기를 접는다. */
function ProviderHiddenFields({
  row,
  draft,
  onToggle,
}: {
  row: ProviderRow;
  draft: HiddenModelEntry[];
  onToggle(entry: HiddenModelEntry, hidden: boolean): void;
}) {
  const wholeProvider = draft.some(
    (entry) => entry.provider === row.provider && entry.model === null,
  );
  return (
    <fieldset className="grid gap-2 rounded-md border border-border p-3">
      <legend className="px-1 text-sm font-medium">{row.name}</legend>
      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          className="size-4"
          checked={wholeProvider}
          onChange={(event) =>
            onToggle(
              { provider: row.provider, model: null },
              event.target.checked,
            )
          }
        />
        {row.name} 전체 숨기기
      </label>
      {wholeProvider ? (
        <p className="text-sm text-muted-foreground">
          모델 {row.models.length}개가 모두 숨겨져요.
        </p>
      ) : (
        // 모델이 수십 개인 제공사가 있어 접어 둔다. 적으면 펼친 채로 보인다.
        <details open={row.models.length <= MODELS_OPEN_LIMIT}>
          <summary className="cursor-pointer text-sm text-muted-foreground">
            모델 {row.models.length}개
          </summary>
          <div className="mt-2 grid gap-2">
            {row.models.map((model) => (
              <label key={model} className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  className="size-4"
                  checked={draft.some((entry) =>
                    sameHiddenEntry(entry, {
                      provider: row.provider,
                      model,
                    }),
                  )}
                  onChange={(event) =>
                    onToggle(
                      { provider: row.provider, model },
                      event.target.checked,
                    )
                  }
                />
                <span className="min-w-0 break-all">{model} 숨기기</span>
              </label>
            ))}
          </div>
        </details>
      )}
    </fieldset>
  );
}

/**
 * 그룹이 숨길 모델 제공사와 모델을 고른다. 저장을 눌러야 목록 전체가 한 번에 바뀐다.
 *
 * <p>이 에이전트의 목록에 없는 숨김 항목도 따로 보인다. 저장은 목록을 통째로 바꾸므로, 보이지 않는 항목을
 * 그대로 두지 않으면 다른 에이전트의 목록에만 있는 숨김이 지워진다.
 */
export function ModelHiddenForm({ settings, onSaved }: Props) {
  const saved = settings.hidden.entries;
  const providers = settings.catalog.providers;
  const [draft, setDraft] = useState<HiddenModelEntry[]>(saved);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(
    null,
  );

  const unlisted = draft.filter((entry) => {
    const row = providers.find((item) => item.provider === entry.provider);
    if (row === undefined) return true;
    return entry.model !== null && !row.models.includes(entry.model);
  });
  const changed =
    draft.length !== saved.length ||
    draft.some((entry) => !saved.some((item) => sameHiddenEntry(item, entry)));

  function toggle(entry: HiddenModelEntry, hidden: boolean) {
    setMessage(null);
    setDraft((current) => {
      const rest = current.filter((item) => !sameHiddenEntry(item, entry));
      return hidden ? [...rest, entry] : rest;
    });
  }

  async function save() {
    setSaving(true);
    setMessage(null);
    const result = await saveHiddenModels(draft);
    setSaving(false);
    if (!result.ok) {
      setMessage({ ok: false, text: result.message });
      return;
    }
    onSaved(draft);
    setMessage({ ok: true, text: "숨김 설정을 저장했어요." });
  }

  return (
    <div className="mt-6 grid gap-3">
      <div>
        <h3 className="text-sm font-medium">모델 숨김</h3>
        <p className="mt-1 text-sm text-muted-foreground">
          표시한 모델 제공사와 모델은 그룹의 모든 사용자와 에이전트에서 고를 수
          없어요. 새로 생긴 모델은 숨기기 전까지 보여요.
        </p>
      </div>
      {providers.map((row) => (
        <ProviderHiddenFields
          key={row.provider}
          row={row}
          draft={draft}
          onToggle={toggle}
        />
      ))}
      {unlisted.length > 0 ? (
        <fieldset className="grid gap-2 rounded-md border border-border p-3">
          <legend className="px-1 text-sm font-medium">
            이 에이전트의 목록에 없는 숨김
          </legend>
          {unlisted.map((entry) => (
            <label
              key={modelKey(entry.provider, entry.model ?? "")}
              className="flex items-center gap-2 text-sm"
            >
              <input
                type="checkbox"
                className="size-4"
                checked
                onChange={() => toggle(entry, false)}
              />
              <span className="min-w-0 break-all">
                {entry.model === null
                  ? `${providerLabel(entry.provider)} 전체 숨기기`
                  : `${providerLabel(entry.provider)} ${entry.model} 숨기기`}
              </span>
            </label>
          ))}
        </fieldset>
      ) : null}
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
          type="button"
          size="sm"
          variant="outline"
          disabled={!changed}
          loading={saving}
          loadingText="저장하는 중"
          onClick={() => void save()}
        >
          숨김 저장
        </Button>
      </div>
    </div>
  );
}
