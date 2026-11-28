"use client";

import { useState } from "react";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { describeFailure } from "@/components/error-message";
import {
  agentControlSwitch,
  setAgentControl,
  type AgentControl,
  type BrowserView,
} from "@/lib/browser-api";

/**
 * 「에이전트가 이 브라우저를 쓰게 하기」 스위치다. 처음에는 꺼져 있다. 켜면 에이전트가 탭을 보고, 사용자가 승인 카드로 승인한 동작만 한다.
 * 동작은 `docs/features/agent-browser/design.md` 의 「PR1 스위치」 가 갖는다.
 */
export function AgentControlSwitch({
  control,
  onChange,
}: {
  control: AgentControl;
  onChange(next: BrowserView): void;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const shown = agentControlSwitch(control);

  async function save(enabled: boolean) {
    setSaving(true);
    setError(null);
    try {
      const response = await setAgentControl(enabled);
      if (response.ok) onChange((await response.json()) as BrowserView);
      else setError(await describeFailure(response));
    } catch {
      setError("요청을 보내지 못했어요. 잠시 뒤 다시 해 주세요.");
    }
    setSaving(false);
  }

  return (
    <div className="space-y-1 border-t border-border pt-3">
      <div className="flex items-center justify-between gap-4">
        <Label htmlFor="browser-agent-control">
          에이전트가 이 브라우저를 쓰게 하기
        </Label>
        <Switch
          id="browser-agent-control"
          aria-label="에이전트가 이 브라우저를 쓰게 하기"
          checked={shown.checked}
          disabled={shown.locked}
          loading={saving}
          onCheckedChange={(next) => void save(next)}
        />
      </div>
      <p className="text-sm text-muted-foreground">
        켜면 에이전트가 열린 탭을 보고, 승인한 탭만 열고 닫아요. 로그인한
        사이트의 제목과 주소를 읽을 수 있어요.
      </p>
      {shown.note ? (
        <p className="text-sm text-muted-foreground">{shown.note}</p>
      ) : null}
      {error ? (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}
