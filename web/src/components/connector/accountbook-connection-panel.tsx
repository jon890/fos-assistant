"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { AccountbookConnection } from "@/lib/connection";

const ERROR_MESSAGES: Record<string, string> = {
  ACCOUNTBOOK_TOKEN_REJECTED: "가계부 토큰을 확인하지 못했어요. 토큰을 다시 확인해 주세요.",
  ACCOUNTBOOK_FAMILY_FORBIDDEN: "선택한 가족에 접근할 수 없어요. 가족 식별자를 다시 확인해 주세요.",
  ACCOUNTBOOK_UNAVAILABLE: "가계부에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  CONNECTOR_OPERATION_FAILED: "연결 설정을 적용하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  FORBIDDEN: "이 작업을 관리할 수 없어요.",
  VALIDATION_FAILED: "입력 내용을 다시 확인해 주세요.",
};

function errorMessage(body: unknown): string {
  const code = typeof body === "object" && body !== null && "code" in body && typeof body.code === "string"
    ? body.code : "";
  return ERROR_MESSAGES[code] ?? "요청을 처리하지 못했어요.";
}

async function request<T>(path: string, init?: RequestInit): Promise<{ data: T } | { error: string }> {
  try {
    const response = await fetch(path, { ...init, cache: "no-store" });
    const body: unknown = await response.json().catch(() => null);
    return response.ok ? { data: body as T } : { error: errorMessage(body) };
  } catch {
    return { error: "연결하지 못했어요. 잠시 뒤 다시 시도해 주세요." };
  }
}

function stateLabel(status: AccountbookConnection["status"]) {
  return { DISCONNECTED: "연결 안 됨", PENDING: "준비 중", READY: "연결됨" }[status];
}

export function AccountbookConnectionPanel({ initialConnection }: { initialConnection: AccountbookConnection | null }) {
  const [connection, setConnection] = useState(initialConnection);
  const [token, setToken] = useState("");
  const [familyUuid, setFamilyUuid] = useState(initialConnection?.familyUuid ?? "");
  const [pending, setPending] = useState<"save" | "check" | "disconnect" | null>(null);
  const [error, setError] = useState<string | null>(initialConnection ? null : "연결 상태를 읽지 못했어요. 다시 확인해 주세요.");
  const busy = pending !== null;
  useEffect(() => {
    const refresh = () => { void request<AccountbookConnection>("/api/connections/accountbook").then((result) => {
      if ("data" in result) setConnection(result.data); else setError(result.error);
    }); };
    window.addEventListener("accountbook-connection-updated", refresh);
    return () => window.removeEventListener("accountbook-connection-updated", refresh);
  }, []);

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    const submittedToken = token;
    setToken("");
    if (submittedToken.trim().length === 0) {
      setError("가계부 토큰을 입력해 주세요.");
      return;
    }
    setPending("save");
    setError(null);
    const result = await request<AccountbookConnection>("/api/connections/accountbook", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: submittedToken, ...(familyUuid.trim() ? { familyUuid: familyUuid.trim() } : {}) }),
    });
    setPending(null);
    if ("error" in result) return setError(result.error);
    setConnection(result.data);
    setFamilyUuid(result.data.familyUuid ?? "");
  }

  async function check() {
    if (busy) return;
    setPending("check"); setError(null);
    const result = await request<AccountbookConnection>("/api/connections/accountbook/check", { method: "POST" });
    setPending(null);
    if ("error" in result) return setError(result.error);
    setConnection(result.data);
  }

  async function disconnect() {
    if (busy) return;
    setPending("disconnect"); setError(null);
    const result = await request<AccountbookConnection>("/api/connections/accountbook", { method: "DELETE" });
    setPending(null);
    if ("error" in result) return setError(result.error);
    setConnection(result.data); setFamilyUuid("");
  }

  const status = connection?.status;
  return <Card>
    <CardHeader>
      <CardTitle>가계부 연결</CardTitle>
      <CardDescription>내 가계부 조회를 위한 토큰을 연결해요.</CardDescription>
    </CardHeader>
    <CardContent className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-muted px-3 py-2 text-sm">
        <span>상태: <strong>{status ? stateLabel(status) : "확인 필요"}</strong></span>
        {connection?.tokenPrefix ? <span>등록한 토큰: {connection.tokenPrefix}</span> : null}
      </div>
      {connection?.checkedAt ? <p className="text-sm text-muted-foreground">마지막 확인: {new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Seoul" }).format(new Date(connection.checkedAt))}</p> : null}
      {connection?.restartRequired ? <p role="status" className="rounded-md border border-border bg-muted p-3 text-sm">
        관리자가 실행 반영을 확인할 때까지 기다려 주세요.
      </p> : null}
      {status === "READY" && connection?.agentCode ? <p className="text-sm">
        <Link href={`/agents/${connection.agentCode}`} className="text-primary underline-offset-4 hover:underline">가계부 에이전트 열기</Link>
      </p> : null}
      <form onSubmit={save} className="space-y-3">
        <div className="space-y-2"><Label htmlFor="accountbook-token">가계부 토큰</Label>
          <Input id="accountbook-token" name="token" type="password" autoComplete="off" value={token} disabled={busy}
            onChange={(event) => setToken(event.target.value)} /></div>
        <div className="space-y-2"><Label htmlFor="accountbook-family">가족 식별자 (선택)</Label>
          <Input id="accountbook-family" name="familyUuid" value={familyUuid} disabled={busy}
            onChange={(event) => setFamilyUuid(event.target.value)} /></div>
        <Button type="submit" disabled={busy} loading={pending === "save"} loadingText="연결하는 중…">{status === "READY" ? "토큰 바꾸기" : "연결하기"}</Button>
      </form>
      {status === "PENDING" ? <Button disabled={busy} variant="outline" onClick={() => void check()} loading={pending === "check"} loadingText="확인하는 중…">연결 다시 확인</Button> : null}
      {status && status !== "DISCONNECTED" ? <Button disabled={busy} variant="destructive" onClick={() => void disconnect()} loading={pending === "disconnect"} loadingText="해제하는 중…">연결 해제</Button> : null}
      {!connection ? <Button disabled={busy} variant="outline" onClick={() => window.dispatchEvent(new Event("accountbook-connection-updated"))}>상태 다시 읽기</Button> : null}
      {status === "DISCONNECTED" ? <p className="text-sm text-muted-foreground">가계부 설정에서 이 토큰을 폐기하면 즉시 끊겨요.</p> : null}
      {error ? <p role="alert" className="rounded-md border border-border bg-muted p-3 text-sm">{error}</p> : null}
    </CardContent>
  </Card>;
}
