"use client";

import { useEffect, useState } from "react";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Switch } from "@/components/ui/switch";
import { describeFailure } from "@/components/error-message";
import {
  changeAutoVaultSetting,
  fetchAutoVaultSetting,
  VAULT_ERRORS,
} from "@/lib/artifact-vault-api";

const LOAD_FAILED = "자동 보관 설정을 불러오지 못했어요.";
const SAVE_FAILED = "자동 보관 설정을 바꾸지 못했어요. 다시 시도해 주세요.";

/**
 * 「보관한 결과물」 위에 두는 자동 보관 켜고 끄기다(#530 PR 3).
 *
 * <p>켜면 에이전트가 답과 함께 만든 결과물을 그 답이 끝날 때 이 보관함에 둔다. 끄면 다음 답부터 두지 않고 이미 보관한 것은
 * 그대로다. 기본값은 켜짐이고 Control Plane 이 정한다.
 */
export function AutoVaultSetting() {
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const response = await fetchAutoVaultSetting();
        if (!active) return;
        if (!response.ok) {
          setError(await describeFailure(response, VAULT_ERRORS));
          return;
        }
        const body = (await response.json()) as { enabled: boolean };
        if (active) setEnabled(body.enabled);
      } catch {
        if (active) setError(LOAD_FAILED);
      }
    })();
    return () => {
      active = false;
    };
  }, []);

  async function change(next: boolean) {
    if (saving) return;
    setSaving(true);
    setError(null);
    try {
      const response = await changeAutoVaultSetting(next);
      if (!response.ok) {
        setError(await describeFailure(response, VAULT_ERRORS));
        return;
      }
      setEnabled(((await response.json()) as { enabled: boolean }).enabled);
    } catch {
      setError(SAVE_FAILED);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div
      data-testid="auto-vault-setting"
      className="space-y-2 rounded-md border border-border p-3"
    >
      {enabled === null ? null : (
        <div className="flex items-center justify-between gap-4">
          <Label htmlFor="auto-vault-enabled">결과물 자동 보관</Label>
          <Switch
            id="auto-vault-enabled"
            aria-label="결과물 자동 보관"
            checked={enabled}
            loading={saving}
            onCheckedChange={(next) => void change(next)}
          />
        </div>
      )}
      <p className="text-sm text-muted-foreground">
        켜 두면 답이 끝날 때 그 답의 결과물을 여기에 암호화해 둬요. 지운
        보관본은 다시 만들지 않아요.
      </p>
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
    </div>
  );
}
