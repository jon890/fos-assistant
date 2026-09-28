"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { AgentModelList } from "@/components/admin/agent-model-list";
import { VisibilityConfirm } from "@/components/admin/visibility-confirm";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { describeError } from "@/components/error-message";
import {
  GROUP_VISIBILITY,
  PRIVATE_VISIBILITY,
  type AdminAgent,
} from "@/lib/agent";

type AgentAction = "private" | "group" | "enabled" | "address" | "sync" | "models";

type Props = { initialAgent: AdminAgent; ownerEmail: string };

async function payload<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

/** 관리자가 에이전트 하나의 실행과 연결 설정을 고친다. */
export function AgentAdminSection({ initialAgent, ownerEmail }: Props) {
  const router = useRouter();
  const [agent, setAgent] = useState(initialAgent);
  const [pending, setPending] = useState<AgentAction | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmingGroupVisibility, setConfirmingGroupVisibility] = useState(false);
  const busy = pending !== null;

  async function update(
    changes: Partial<Pick<AdminAgent, "enabled" | "visibility" | "apiBaseUrl">>,
    action: AgentAction,
  ): Promise<boolean> {
    setPending(action);
    setError(null);
    try {
      const visibility = changes.visibility ?? agent.visibility;
      const response = await fetch(`/api/admin/agents/${agent.code}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          enabled: changes.enabled ?? agent.enabled,
          visibility,
          // 비공개 상태를 유지하는 수정은 기존 주인을 그대로 둔다. 그룹 공개에서 비공개로 바꿀 때만
          // 요청자를 새 주인으로 정한다.
          ownerEmail: visibility === PRIVATE_VISIBILITY && agent.visibility === GROUP_VISIBILITY
            ? ownerEmail
            : null,
          apiBaseUrl: changes.apiBaseUrl,
        }),
      });
      if (!response.ok) {
        const result = await payload<{ code: string; message: string }>(response);
        setError(describeError(result.code, result.message));
        return false;
      }
      setAgent(await payload<AdminAgent>(response));
      if (changes.visibility !== undefined) router.refresh();
      return true;
    } finally {
      setPending(null);
    }
  }

  async function saveAddress(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await update({ apiBaseUrl: String(form.get("apiBaseUrl") ?? "") }, "address");
  }

  async function syncModel() {
    setPending("sync");
    setError(null);
    try {
      const response = await fetch(`/api/admin/agents/${agent.code}/sync-model`, { method: "POST" });
      if (!response.ok) {
        const result = await payload<{ code: string; message: string }>(response);
        setError(describeError(result.code, result.message));
      } else {
        const result = await payload<{ model: string; modelSyncedAt: string }>(response);
        setAgent((current) => ({ ...current, model: result.model, modelSyncedAt: result.modelSyncedAt }));
      }
    } finally {
      setPending(null);
    }
  }

  async function saveModels(models: { provider: string; model: string }[]) {
    setPending("models");
    setError(null);
    try {
      const response = await fetch(`/api/admin/agents/${agent.code}/models`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ models }),
      });
      if (response.ok) {
        setAgent((current) => ({
          ...current,
          models: models.map((model, index) => ({ ...model, rank: index + 1 })),
        }));
      } else {
        const result = await payload<{ code: string; message: string }>(response);
        setError(describeError(result.code, result.message));
      }
    } finally {
      setPending(null);
    }
  }

  async function confirmGroupVisibility() {
    if (await update({ visibility: GROUP_VISIBILITY }, "group")) {
      setConfirmingGroupVisibility(false);
    }
  }

  return (
    <section aria-label="관리" className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold">관리</h2>
          <p className="mt-1 text-sm text-muted-foreground">사용 여부와 연결 설정을 고칠 수 있어요.</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Badge variant="outline">{agent.visibility === PRIVATE_VISIBILITY ? "나만" : "그룹 공개"}</Badge>
          <Badge variant={agent.enabled ? "outline" : "default"}>{agent.enabled ? "사용 중" : "꺼짐"}</Badge>
        </div>
      </div>
      {error ? <p role="alert" className="mt-4 rounded-md bg-muted p-3 text-sm">{error}</p> : null}
      <form onSubmit={(event) => void saveAddress(event)} className="mt-4">
        <div className="grid gap-1.5">
          <Label htmlFor="agent-api-base-url">Hermes API 주소</Label>
          <Input
            id="agent-api-base-url"
            name="apiBaseUrl"
            key={agent.apiBaseUrl}
            defaultValue={agent.apiBaseUrl}
            aria-label={`${agent.name} Hermes API 주소`}
          />
        </div>
        <p className="mt-2 text-xs text-muted-foreground">저장하기 전에 이 주소가 응답하는지 확인해요.</p>
        <Button type="submit" size="sm" variant="outline" disabled={busy} loading={pending === "address"} loadingText="확인하는 중" className="mt-2">주소 저장</Button>
      </form>
      <div className="mt-4 flex flex-wrap gap-2">
        <Button size="sm" variant="outline" disabled={busy} loading={pending === "private"} loadingText="바꾸는 중" onClick={() => agent.visibility === PRIVATE_VISIBILITY ? setConfirmingGroupVisibility(true) : void update({ visibility: PRIVATE_VISIBILITY }, "private")}>
          {agent.visibility === PRIVATE_VISIBILITY ? "그룹 공개로 변경" : "나만으로 변경"}
        </Button>
        <Button size="sm" variant="ghost" disabled={busy} loading={pending === "enabled"} loadingText={agent.enabled ? "중지하는 중" : "켜는 중"} onClick={() => void update({ enabled: !agent.enabled }, "enabled")}>
          {agent.enabled ? "사용 중지" : "다시 사용"}
        </Button>
        <Button size="sm" variant="outline" disabled={busy} loading={pending === "sync"} loadingText="읽는 중" onClick={() => void syncModel()}>
          모델 다시 읽기
        </Button>
      </div>
      <AgentModelList agentCode={agent.code} models={agent.models} busy={busy} saving={pending === "models"} onSave={(models) => void saveModels(models)} />
      {confirmingGroupVisibility ? <VisibilityConfirm agent={agent} busy={pending === "group"} onCancel={() => setConfirmingGroupVisibility(false)} onConfirm={() => void confirmGroupVisibility()} /> : null}
    </section>
  );
}
