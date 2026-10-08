"use client";

import { useEffect, useState } from "react";
import { describeError } from "@/components/error-message";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Switch } from "@/components/ui/switch";
import { formatWhen } from "@/lib/format";
import {
  fetchProactiveLoopSetting,
  saveProactiveLoopSetting,
  type ProactiveLoopSetting,
} from "@/lib/proactive-check";

const HOUR_MS = 60 * 60 * 1000;
const READ_FAILED = "설정을 읽지 못했어요. 잠시 뒤 다시 열어 주세요.";
const SAVE_FAILED = "설정을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.";

/** 응답이 실패면 오류 코드의 문구를, 아니면 `null` 을 준다. */
async function failureOf(
  response: Response,
  fallback: string,
): Promise<string | null> {
  if (response.ok) return null;
  const payload = (await response.json().catch(() => ({}))) as {
    code?: string;
    message?: string;
  };
  return describeError(
    payload.code ?? "INTERNAL_ERROR",
    payload.message ?? fallback,
  );
}

/**
 * 매일 깨운 뒤 먼저 다룰 문제를 고르는 매일 루프 설정이다. 저장은 스위치와 단추가 바로 보낸다.
 * 매일 깨우기 설정의 `form` 밖에 두어 중첩 `form` 을 만들지 않는다.
 */
export function AgentProactiveLoopSetting({ code }: { code: string }) {
  const [setting, setSetting] = useState<ProactiveLoopSetting | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const response = await fetchProactiveLoopSetting(code);
        const failure = await failureOf(response, READ_FAILED);
        if (cancelled) return;
        if (failure) setError(failure);
        else setSetting((await response.json()) as ProactiveLoopSetting);
      } catch {
        if (!cancelled) setError(READ_FAILED);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [code]);

  async function save(
    next: Pick<ProactiveLoopSetting, "enabled" | "snoozedUntil">,
  ) {
    if (saving) return;
    setSaving(true);
    setError(null);
    try {
      const response = await saveProactiveLoopSetting(code, next);
      const failure = await failureOf(response, SAVE_FAILED);
      if (failure) setError(failure);
      else setSetting((await response.json()) as ProactiveLoopSetting);
    } catch {
      setError(SAVE_FAILED);
    } finally {
      setSaving(false);
    }
  }

  // 서버가 지난 쉬기는 null 로 준다.
  const snoozeFor = (hours: number) =>
    void save({
      enabled: true,
      snoozedUntil: new Date(Date.now() + hours * HOUR_MS).toISOString(),
    });

  return (
    <div className="mt-6 border-t border-border pt-4">
      {error ? (
        <Notice variant="error" role="alert" className="mt-3">
          {error}
        </Notice>
      ) : null}
      {setting === null ? null : (
        <div className="mt-3 flex flex-col gap-3">
          <div className="flex items-center justify-between gap-4">
            <Label htmlFor="proactive-loop-enabled">
              깨운 뒤 먼저 다룰 문제 고르기
            </Label>
            <Switch
              id="proactive-loop-enabled"
              aria-label="깨운 뒤 먼저 다룰 문제 고르기"
              checked={setting.enabled}
              loading={saving}
              disabled={!setting.available && !setting.enabled}
              onCheckedChange={(enabled) =>
                void save({ enabled, snoozedUntil: setting.snoozedUntil })
              }
            />
          </div>
          {setting.available ? (
            <p className="text-sm text-muted-foreground">
              매일 깨우기가 찾은 문제 가운데 먼저 다룰 것을 골라 지금 화면에
              보여요.
            </p>
          ) : (
            <Notice variant="info">이 설치에서는 아직 쓸 수 없어요.</Notice>
          )}
          {setting.enabled ? (
            <div className="flex flex-wrap items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={saving}
                onClick={() => snoozeFor(24)}
              >
                하루 쉬기
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={saving}
                onClick={() => snoozeFor(7 * 24)}
              >
                일주일 쉬기
              </Button>
              {setting.snoozedUntil ? (
                <>
                  <p className="text-sm text-muted-foreground">
                    {formatWhen(setting.snoozedUntil)}까지 쉬어요
                  </p>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={saving}
                    onClick={() =>
                      void save({ enabled: true, snoozedUntil: null })
                    }
                  >
                    쉬기 끝내기
                  </Button>
                </>
              ) : null}
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}
