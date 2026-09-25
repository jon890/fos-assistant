"use client";

import { useCallback, useEffect, useState } from "react";
import { AgentForm } from "@/components/admin/agent-form";
import { AgentList } from "@/components/admin/agent-list";
import { VisibilityConfirm } from "@/components/admin/visibility-confirm";
import { describeError } from "@/components/error-message";
import {
  FAMILY_VISIBILITY,
  PRIVATE_VISIBILITY,
  formatRemaining,
  type AdminAgent,
  type BlockedProvider,
} from "@/lib/agent";

export type { AdminAgent } from "@/lib/agent";

type Props = { initialAgents: AdminAgent[]; ownerEmail: string };

async function payload<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

export function AgentAdminPanel({ initialAgents, ownerEmail }: Props) {
  const [agents, setAgents] = useState(initialAgents);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmingAgent, setConfirmingAgent] = useState<AdminAgent | null>(null);
  const [blocked, setBlocked] = useState<BlockedProvider[]>([]);

  const reloadBlocked = useCallback(async () => {
    const response = await fetch("/api/admin/providers/blocked");
    setBlocked(response.ok ? await payload<BlockedProvider[]>(response) : []);
  }, []);

  useEffect(() => {
    void reloadBlocked();
  }, [reloadBlocked]);

  async function reload() {
    const response = await fetch("/api/admin/agents");
    if (!response.ok) throw await payload<{ code: string; message: string }>(response);
    setAgents(await payload<AdminAgent[]>(response));
    await reloadBlocked();
  }

  async function create(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    setBusy(true);
    setError(null);
    try {
      const form = new FormData(formElement);
      const visibility = String(form.get("visibility"));
      const response = await fetch("/api/admin/agents", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          code: form.get("code"), name: form.get("name"), hermesProfile: form.get("hermesProfile"),
          apiBaseUrl: form.get("apiBaseUrl"), provider: form.get("provider"), costMode: form.get("costMode"),
          credentialScope: form.get("credentialScope"), visibility,
          ownerEmail: visibility === PRIVATE_VISIBILITY ? form.get("ownerEmail") : null,
        }),
      });
      if (response.ok) {
        formElement.reset();
        await reload();
      } else {
        const result = await payload<{ code: string; message: string }>(response);
        setError(describeError(result.code, result.message));
      }
    } finally {
      setBusy(false);
    }
  }

  async function update(agent: AdminAgent, changes: Partial<Pick<AdminAgent, "enabled" | "visibility">>): Promise<boolean> {
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(`/api/admin/agents/${agent.code}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          enabled: changes.enabled ?? agent.enabled,
          visibility: changes.visibility ?? agent.visibility,
          ownerEmail: (changes.visibility ?? agent.visibility) === PRIVATE_VISIBILITY ? ownerEmail : null,
        }),
      });
      if (response.ok) {
        await reload();
        return true;
      } else {
        const result = await payload<{ code: string; message: string }>(response);
        setError(describeError(result.code, result.message));
        return false;
      }
    } finally {
      setBusy(false);
    }
  }

  /**
   * 에이전트의 Hermes 주소를 바꾼다.
   *
   * <p>실패 이유는 그 카드 안에 적어야 해서 전역 오류로 올리지 않고 돌려준다. 「저장하지 못했다」만
   * 으로는 주소가 틀린 것인지 Hermes 가 내려간 것인지 모른다.
   */
  async function changeApiBaseUrl(agent: AdminAgent, apiBaseUrl: string): Promise<string | null> {
    setBusy(true);
    try {
      const response = await fetch(`/api/admin/agents/${agent.code}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          enabled: agent.enabled,
          visibility: agent.visibility,
          ownerEmail: agent.visibility === PRIVATE_VISIBILITY ? ownerEmail : null,
          apiBaseUrl,
        }),
      });
      if (!response.ok) {
        const result = await payload<{ code: string; message: string }>(response);
        return describeError(result.code, result.message);
      }
      await reload();
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function syncModel(agent: AdminAgent) {
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(`/api/admin/agents/${agent.code}/sync-model`, { method: "POST" });
      if (response.ok) await reload();
      else {
        const result = await payload<{ code: string; message: string }>(response);
        setError(describeError(result.code, result.message));
      }
    } finally {
      setBusy(false);
    }
  }

  async function saveModels(agent: AdminAgent, models: { provider: string; model: string }[]) {
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(`/api/admin/agents/${agent.code}/models`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ models }),
      });
      if (response.ok) await reload();
      else {
        const result = await payload<{ code: string; message: string }>(response);
        setError(describeError(result.code, result.message));
      }
    } finally {
      setBusy(false);
    }
  }

  function requestVisibilityChange(agent: AdminAgent) {
    if (agent.visibility === PRIVATE_VISIBILITY) setConfirmingAgent(agent);
    else void update(agent, { visibility: PRIVATE_VISIBILITY });
  }

  async function confirmFamilyVisibility() {
    if (!confirmingAgent) return;
    if (await update(confirmingAgent, { visibility: FAMILY_VISIBILITY })) {
      setConfirmingAgent(null);
    }
  }

  return (
    <div className="mx-auto w-full max-w-4xl">
      <h1 className="mb-2 text-xl font-semibold">에이전트 관리</h1>
      <p className="mb-6 max-w-2xl text-sm leading-6 text-muted">
        공개 범위는 보안 설정이다. 가족 공개로 바꾸면 모든 사용자가 이 에이전트로 대화할 수 있다.
        연결된 도구와 자료도 함께 쓸 수 있는지 확인해야 한다.
      </p>
      {blocked.length > 0 ? (
        <p className="mb-4 rounded-md bg-surface p-3 text-sm" data-testid="blocked-providers">
          막힌 provider:{" "}
          {blocked
            .map((row) => `${row.provider} (${formatRemaining(row.remainingSeconds)})`)
            .join(", ")}
        </p>
      ) : null}
      <AgentForm ownerEmail={ownerEmail} busy={busy} onCreate={(event) => void create(event)} />
      {error ? <p className="mb-4 rounded-md bg-surface p-3 text-sm">{error}</p> : null}
      <AgentList
        agents={agents}
        busy={busy}
        onVisibilityChange={requestVisibilityChange}
        onEnabledChange={(agent) => void update(agent, { enabled: !agent.enabled })}
        onSyncModel={(agent) => void syncModel(agent)}
        onSaveModels={(agent, models) => void saveModels(agent, models)}
        onApiBaseUrlChange={changeApiBaseUrl}
      />
      {confirmingAgent ? (
        <VisibilityConfirm agent={confirmingAgent} busy={busy} onCancel={() => setConfirmingAgent(null)} onConfirm={() => void confirmFamilyVisibility()} />
      ) : null}
    </div>
  );
}
