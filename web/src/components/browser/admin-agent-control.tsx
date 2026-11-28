"use client";

import { useEffect, useState } from "react";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import {
  fetchAdminAgentControl,
  setAdminAgentControl,
} from "@/lib/browser-api";

/**
 * 관리자가 그룹 전체의 에이전트 브라우저 조작을 켜고 끈다. 끄면 사용자가 켜 둔 스위치는 남지만 다음 호출과 기다리는 카드의 실행이 거절된다.
 * 동작은 `docs/features/agent-browser/design.md` 의 「PR1 스위치」 가 갖는다.
 */
export function AdminAgentControl() {
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void fetchAdminAgentControl()
      .then(async (response) =>
        response.ok
          ? ((await response.json()) as { enabled: boolean }).enabled
          : null,
      )
      .catch(() => null)
      .then((value) => {
        if (value === null)
          setError("스위치 상태를 읽지 못했어요. 화면을 다시 열어 주세요.");
        else setEnabled(value);
      });
  }, []);

  async function save(next: boolean) {
    setSaving(true);
    setError(null);
    try {
      const response = await setAdminAgentControl(next);
      if (response.ok)
        setEnabled(((await response.json()) as { enabled: boolean }).enabled);
      else setError("스위치를 바꾸지 못했어요. 잠시 뒤 다시 해 주세요.");
    } catch {
      setError("요청을 보내지 못했어요. 잠시 뒤 다시 해 주세요.");
    }
    setSaving(false);
  }

  return (
    <section
      aria-label="에이전트의 브라우저 조작"
      className="mb-4 space-y-1 rounded-md border border-border p-3"
    >
      <div className="flex items-center justify-between gap-4">
        <Label htmlFor="admin-browser-agent-control">
          에이전트의 브라우저 조작 허용
        </Label>
        <Switch
          id="admin-browser-agent-control"
          aria-label="에이전트의 브라우저 조작 허용"
          checked={enabled ?? false}
          disabled={enabled === null}
          loading={saving}
          onCheckedChange={(next) => void save(next)}
        />
      </div>
      <p className="text-sm text-muted-foreground">
        끄면 사용자가 켜 두었어도 에이전트가 브라우저 탭을 보거나 열고 닫지
        못해요.
      </p>
      {error ? (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
    </section>
  );
}
