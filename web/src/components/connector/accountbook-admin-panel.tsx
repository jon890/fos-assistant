"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import type { AdminAccountbookConnection } from "@/lib/connection";

export function AccountbookAdminPanel({ initialConnections }: { initialConnections: AdminAccountbookConnection[] }) {
  const [connections, setConnections] = useState(initialConnections);
  const [pending, setPending] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const waiting = connections.filter((connection) => connection.restartRequired || connection.status === "PENDING");

  useEffect(() => {
    void fetch("/api/admin/connections/accountbook", { cache: "no-store" })
      .then((response) => { if (!response.ok) throw new Error(); return response.json(); })
      .then((items: unknown) => { if (Array.isArray(items)) setConnections(items as AdminAccountbookConnection[]); })
      .catch(() => setError("연결 목록을 읽지 못했어요. 화면을 다시 열어 주세요."));
  }, []);

  async function confirm(userId: number) {
    if (pending !== null) return;
    setPending(userId); setError(null);
    try {
      const response = await fetch(`/api/admin/connections/accountbook/${userId}/confirm`, { method: "POST" });
      if (!response.ok) throw new Error();
      const result = await response.json() as AdminAccountbookConnection;
      if (result.userId !== userId || result.restartRequired !== false || !["READY", "DISCONNECTED"].includes(result.status)) throw new Error();
      setConnections((items) => items.map((item) => item.userId === userId ? { ...item, ...result, displayName: item.displayName } : item));
      window.dispatchEvent(new Event("accountbook-connection-updated"));
    } catch { setError("반영 상태를 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요."); }
    finally { setPending(null); }
  }

  return <Card className="mt-8" data-testid="accountbook-admin-panel">
    <CardHeader><CardTitle>가계부 연결 반영 확인</CardTitle><CardDescription>실행 반영을 마친 뒤 사용자별 연결을 확인해요.</CardDescription></CardHeader>
    <CardContent className="space-y-3">
      {waiting.length === 0 ? <p className="text-sm text-muted-foreground">반영 확인이 필요한 연결이 없어요.</p> : <ul className="space-y-2">
        {waiting.map((connection) => <li key={connection.userId} className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-border p-3">
          <div className="text-sm"><p className="font-medium">{connection.displayName}</p><p className="text-muted-foreground">{connection.status === "PENDING" ? "준비 중" : "연결 해제"}</p></div>
          <Button disabled={pending !== null} variant="outline" onClick={() => void confirm(connection.userId)} loading={pending === connection.userId} loadingText="확인 중…">반영 완료 확인</Button>
        </li>)}
      </ul>}
      {error ? <p role="alert" className="rounded-md border border-border bg-muted p-3 text-sm">{error}</p> : null}
    </CardContent>
  </Card>;
}
