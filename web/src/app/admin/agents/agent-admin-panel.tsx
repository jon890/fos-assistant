"use client";

import { useState } from "react";
import { describeError } from "@/components/error-message";

export type AdminAgent = {
  id: number;
  code: string;
  name: string;
  hermesProfile: string;
  apiBaseUrl: string;
  provider: string;
  model: string;
  modelSyncedAt: string | null;
  costMode: string;
  credentialScope: string;
  visibility: "PRIVATE" | "FAMILY";
  ownerUserId: number | null;
  enabled: boolean;
};

type Props = { initialAgents: AdminAgent[]; ownerEmail: string };

async function payload<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

export function AgentAdminPanel({ initialAgents, ownerEmail }: Props) {
  const [agents, setAgents] = useState(initialAgents);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function reload() {
    const response = await fetch("/api/admin/agents");
    if (!response.ok) throw await payload<{ code: string; message: string }>(response);
    setAgents(await payload<AdminAgent[]>(response));
  }

  async function create(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    const form = new FormData(event.currentTarget);
    const visibility = String(form.get("visibility"));
    const response = await fetch("/api/admin/agents", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        code: form.get("code"),
        name: form.get("name"),
        hermesProfile: form.get("hermesProfile"),
        apiBaseUrl: form.get("apiBaseUrl"),
        provider: form.get("provider"),
        costMode: form.get("costMode"),
        credentialScope: form.get("credentialScope"),
        visibility,
        ownerEmail: visibility === "PRIVATE" ? form.get("ownerEmail") : null,
      }),
    });
    if (response.ok) {
      event.currentTarget.reset();
      await reload();
    } else {
      const result = await payload<{ code: string; message: string }>(response);
      setError(describeError(result.code, result.message));
    }
    setBusy(false);
  }

  async function update(agent: AdminAgent, changes: Partial<Pick<AdminAgent, "enabled" | "visibility">>) {
    setBusy(true);
    setError(null);
    const response = await fetch(`/api/admin/agents/${agent.code}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        enabled: changes.enabled ?? agent.enabled,
        visibility: changes.visibility ?? agent.visibility,
        ownerEmail: (changes.visibility ?? agent.visibility) === "PRIVATE" ? ownerEmail : null,
      }),
    });
    if (response.ok) await reload();
    else {
      const result = await payload<{ code: string; message: string }>(response);
      setError(describeError(result.code, result.message));
    }
    setBusy(false);
  }

  async function syncModel(agent: AdminAgent) {
    setBusy(true);
    setError(null);
    const response = await fetch(`/api/admin/agents/${agent.code}/sync-model`, { method: "POST" });
    if (response.ok) await reload();
    else {
      const result = await payload<{ code: string; message: string }>(response);
      setError(describeError(result.code, result.message));
    }
    setBusy(false);
  }

  return (
    <>
      <h1 className="mb-4 text-lg font-semibold">에이전트 관리</h1>
      <p className="mb-4 text-sm" style={{ color: "var(--muted)" }}>
        공개 범위는 보안 설정이다. FAMILY로 바꾸면 모든 가족 구성원이 이 에이전트에 연결된 도구를 쓸 수
        있다. 터미널이나 파일 도구가 열린 에이전트는 웹에서 홈서버를 조작할 수 있으므로 공개하면 안 된다.
      </p>

      <form onSubmit={create} className="mb-8 grid gap-3 rounded-lg border p-4" style={{ borderColor: "var(--border)" }}>
        <h2 className="font-semibold">에이전트 등록</h2>
        <div className="grid gap-3 md:grid-cols-2">
          <input name="code" required pattern="[a-z0-9][a-z0-9-]*" placeholder="코드" className="rounded-md border px-3 py-2" />
          <input name="name" required placeholder="이름" className="rounded-md border px-3 py-2" />
          <input name="hermesProfile" required placeholder="Hermes profile" className="rounded-md border px-3 py-2" />
          <input name="apiBaseUrl" required placeholder="Hermes API 주소" className="rounded-md border px-3 py-2" />
          <input name="provider" required placeholder="provider" className="rounded-md border px-3 py-2" />
          <input name="ownerEmail" defaultValue={ownerEmail} placeholder="개인 소유자 이메일" className="rounded-md border px-3 py-2" />
          <select name="costMode" defaultValue="SUBSCRIPTION" className="rounded-md border px-3 py-2">
            <option value="SUBSCRIPTION">구독</option><option value="API">API</option>
          </select>
          <select name="credentialScope" defaultValue="SHARED_HOUSEHOLD" className="rounded-md border px-3 py-2">
            <option value="SHARED_HOUSEHOLD">가족 공유 credential</option><option value="DEDICATED">전용 credential</option>
          </select>
          <label className="text-sm">공개 범위
            <select name="visibility" defaultValue="PRIVATE" className="ml-2 rounded-md border px-3 py-2">
              <option value="PRIVATE">개인</option><option value="FAMILY">가족 공개</option>
            </select>
          </label>
        </div>
        <p className="text-xs" style={{ color: "var(--muted)" }}>모델은 등록할 때 Hermes에서 읽는다.</p>
        <button disabled={busy} className="w-fit rounded-md border px-4 py-2 text-sm disabled:opacity-50">등록</button>
      </form>

      {error ? <p className="mb-4 rounded-md p-3 text-sm" style={{ background: "var(--surface)" }}>{error}</p> : null}

      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead><tr><th>코드</th><th>이름</th><th>모델</th><th>공개 범위</th><th>사용</th><th>마지막 확인</th><th>작업</th></tr></thead>
          <tbody>{agents.map((agent) => (
            <tr key={agent.code} className="border-t" style={{ borderColor: "var(--border)" }}>
              <td className="py-2 pr-3">{agent.code}</td><td className="py-2 pr-3">{agent.name}</td>
              <td className="py-2 pr-3">{agent.model}</td>
              <td className="py-2 pr-3"><button disabled={busy} onClick={() => void update(agent, { visibility: agent.visibility === "PRIVATE" ? "FAMILY" : "PRIVATE" })}>{agent.visibility}</button></td>
              <td className="py-2 pr-3">{agent.enabled ? "사용" : "중지"}</td>
              <td className="py-2 pr-3">{agent.modelSyncedAt ? new Date(agent.modelSyncedAt).toLocaleString("ko-KR") : "-"}</td>
              <td className="flex gap-2 py-2"><button disabled={busy} onClick={() => void update(agent, { enabled: !agent.enabled })}>{agent.enabled ? "중지" : "사용"}</button><button disabled={busy} onClick={() => void syncModel(agent)}>모델 확인</button></td>
            </tr>
          ))}</tbody>
        </table>
      </div>
    </>
  );
}
