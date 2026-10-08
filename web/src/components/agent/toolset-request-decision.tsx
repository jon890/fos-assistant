"use client";

import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { toolsetText } from "@/lib/toolset-label";
import { readRequestResponse, decideToolsetRequest, requestStatusText, type ToolsetRequest } from "@/lib/toolset-request";

/** 관리자는 현재 상태를 확인하고 결정하며 요청자는 결과와 사유를 읽는다. */
export function ToolsetRequestDecision({ initial, admin, onDecided }: {
  initial: ToolsetRequest; admin: boolean; onDecided?(): void;
}) {
  const [row, setRow] = useState(initial);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const text = toolsetText(row.toolset, { label: "도구", description: "" });

  async function decide(approve: boolean) {
    setBusy(true);
    setError(null);
    try {
      const response = await decideToolsetRequest(row.id, approve, reason);
      setRow(await readRequestResponse<ToolsetRequest>(response));
      onDecided?.();
    } catch {
      setError("결정을 저장하지 못했어요. 도구와 권한을 확인하고 다시 시도해 주세요.");
    } finally { setBusy(false); }
  }

  return (
    <section className="mx-auto w-full max-w-2xl rounded-md border border-border p-4" aria-label="도구 사용 요청">
      <h2 className="text-lg font-semibold">도구 사용 요청</h2>
      <p className="mt-3">{row.requesterName}님 · {row.agentName} · {text.label}</p>
      <p className="mt-2 text-sm text-muted-foreground">{text.description}</p>
      <p className="mt-3" role="status">{requestStatusText[row.status]}</p>
      {row.reason ? <p className="mt-2 whitespace-pre-wrap break-words">{row.reason}</p> : null}
      {row.agentDeleted ? <p className="mt-3 text-sm">지운 에이전트의 요청이에요.</p> : <Link href={`/${admin ? "admin/" : ""}agents/${row.agentCode}`} className="mt-3 block underline" prefetch={false}>에이전트 보기</Link>}
      {error ? <Notice variant="error" role="alert" className="mt-4">{error}</Notice> : null}
      {admin && row.status === "PENDING" ? (
        <div className="mt-4">
          <Notice variant="warning">승인하면 이 에이전트가 다음 실행부터 도구를 쓸 수 있어요. 에이전트의 공개 범위와 연결을 확인해 주세요.</Notice>
          {row.toolset === "session_search" ? <Notice variant="warning" className="mt-2">이 도구는 다른 사람과 나눈 대화까지 찾아 읽을 수 있어요.</Notice> : null}
          <label className="mt-4 block text-sm" htmlFor={`rejection-${row.id}`}>거절 사유 한 줄</label>
          <input id={`rejection-${row.id}`} className="mt-2 w-full rounded-md border border-border bg-background p-2" maxLength={200} value={reason} disabled={busy} onChange={(event) => setReason(event.target.value)} />
          <div className="mt-4 flex flex-wrap gap-2">
            <Button loading={busy} disabled={busy} onClick={() => void decide(true)}>승인</Button>
            <Button variant="outline" disabled={busy || !reason.trim()} onClick={() => void decide(false)}>거절</Button>
          </div>
        </div>
      ) : null}
    </section>
  );
}
