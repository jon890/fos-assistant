"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import {
  readConnectorGrants,
  revokeConnectorGrant,
  type ConnectorGrant,
} from "@/lib/connector-action";

function formatExpires(value: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "Asia/Seoul",
  }).format(new Date(value));
}

/**
 * 이 연결에서 묻지 않고 실행하게 허락한 동작을 보이고 허락을 거두게 한다.
 *
 * <p>허락이 없거나 읽지 못하면 아무것도 그리지 않는다. 도구의 원래 이름은 내부 값이라 그리지 않는다.
 */
export function ConnectorGrants({ connectorId }: { connectorId: string }) {
  const [grants, setGrants] = useState<ConnectorGrant[]>([]);
  const [revoking, setRevoking] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let stale = false;
    void readConnectorGrants().then((result) => {
      if (stale || !result.ok) return;
      setGrants(
        result.data.filter((grant) => grant.connectorId === connectorId),
      );
    });
    return () => {
      stale = true;
    };
  }, [connectorId]);

  if (grants.length === 0) return null;

  async function revoke(grantId: number) {
    if (revoking !== null) return;
    setRevoking(grantId);
    setError(null);
    const result = await revokeConnectorGrant(grantId);
    setRevoking(null);
    if (!result.ok) return setError(result.message);
    setGrants((current) =>
      current.filter((grant) => grant.grantId !== grantId),
    );
  }

  return (
    <section className="space-y-2" data-testid="connector-grants">
      <h2 className="text-sm font-semibold">묻지 않고 실행하는 동작</h2>
      <ul className="space-y-2">
        {grants.map((grant) => (
          <li
            key={grant.grantId}
            data-testid="connector-grant"
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border p-3 text-sm"
          >
            <span className="min-w-0">
              <span className="block break-all">
                {grant.title ?? "이름 없는 동작"}
              </span>
              <span className="block text-xs text-muted-foreground">
                {formatExpires(grant.expiresAt)}까지
              </span>
            </span>
            <Button
              size="sm"
              variant="outline"
              data-testid="grant-revoke"
              disabled={revoking !== null}
              loading={revoking === grant.grantId}
              onClick={() => void revoke(grant.grantId)}
            >
              다시 묻기
            </Button>
          </li>
        ))}
      </ul>
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
    </section>
  );
}
