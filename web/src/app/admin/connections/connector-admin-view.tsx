"use client";

import { useEffect, useState } from "react";
import { ConnectorAdminPanel } from "@/components/connector/connector-admin-panel";
import { Notice } from "@/components/ui/notice";
import { readConnectors, type ConnectorSummary } from "@/lib/connection";

/** 커넥터를 읽어 연결 반영 확인 패널을 그린다. 확인할 연결이 없으면 그 안내를 보인다. */
export function ConnectorAdminView() {
  const [connectors, setConnectors] = useState<ConnectorSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void readConnectors().then((result) => {
      if (result.ok) setConnectors(result.data);
      else setError(result.message);
    });
  }, []);

  return (
    <div className="mx-auto w-full max-w-2xl">
      <h1 className="mb-4 text-xl font-semibold">커넥터</h1>
      {error ? (
        <Notice variant="error" role="alert">
          커넥터 목록을 읽지 못했어요. {error}
        </Notice>
      ) : connectors === null ? (
        <p className="text-sm text-muted-foreground">불러오는 중…</p>
      ) : connectors.length === 0 ? (
        <p className="text-sm text-muted-foreground">확인할 연결이 없어요.</p>
      ) : (
        <ConnectorAdminPanel connectors={connectors} />
      )}
    </div>
  );
}
