"use client";

import { useCallback, useEffect, useState } from "react";
import { AgentForm } from "@/components/admin/agent-form";
import { AgentList } from "@/components/admin/agent-list";
import { describeAdminError } from "@/components/error-message";
import {
  PRIVATE_VISIBILITY,
  formatRemaining,
  type AdminAgent,
  type BlockedProvider,
} from "@/lib/agent";

export type { AdminAgent } from "@/lib/agent";

type Props = { initialAgents: AdminAgent[]; ownerEmail: string; currentUserId: number };

async function payload<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

export function AgentAdminPanel({ initialAgents, ownerEmail, currentUserId }: Props) {
  const [agents, setAgents] = useState(initialAgents);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
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
    setCreating(true);
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
        setError(describeAdminError(result.code, result.message));
      }
    } finally {
      setCreating(false);
    }
  }

  return (
    <div className="mx-auto w-full max-w-4xl">
      <h1 className="mb-2 text-xl font-semibold">에이전트</h1>
      <p className="mb-6 max-w-2xl text-sm leading-6 text-muted-foreground">
        공개 범위는 보안 설정이에요. 그룹에 공개하면 그룹의 모든 사용자가 이 에이전트로 대화할 수 있어요.
        연결된 도구와 자료를 함께 써도 되는지 확인해 주세요.
      </p>
      {blocked.length > 0 ? (
        <p className="mb-4 rounded-md bg-muted p-3 text-sm" data-testid="blocked-providers">
          막힌 모델 제공사:{" "}
          {blocked
            .map((row) => `${row.provider} (${formatRemaining(row.remainingSeconds)})`)
            .join(", ")}
        </p>
      ) : null}
      <AgentForm ownerEmail={ownerEmail} busy={creating} creating={creating} onCreate={(event) => void create(event)} />
      {error ? <p className="mb-4 rounded-md bg-muted p-3 text-sm">{error}</p> : null}
      <AgentList
        agents={agents}
        currentUserId={currentUserId}
      />
    </div>
  );
}
