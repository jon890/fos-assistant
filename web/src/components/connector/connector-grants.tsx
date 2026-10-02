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
 * <p>허락이 없으면 아무것도 그리지 않는다. 읽지 못하면 옛 허락을 지우고 읽지 못했다고 알리며 다시 확인하게 한다.
 * 도구의 원래 이름은 내부 값이라 그리지 않는다.
 */
export function ConnectorGrants({
  connectorId,
  refreshKey,
}: {
  connectorId: string;
  /** 바뀌면 허락 목록을 다시 읽는다. 연결 해제나 다시 등록처럼 허락이 사라질 수 있는 뒤에 올린다. */
  refreshKey?: unknown;
}) {
  const [grants, setGrants] = useState<ConnectorGrant[]>([]);
  const [revoking, setRevoking] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** 마지막 읽기가 실패했는지다. 참이면 서버의 실제 허락을 알 수 없다 */
  const [unavailable, setUnavailable] = useState(false);
  /** 다시 확인을 누른 횟수다 */
  const [reloads, setReloads] = useState(0);

  useEffect(() => {
    let stale = false;
    void readConnectorGrants().then((result) => {
      if (stale) return;
      if (!result.ok) {
        setGrants([]);
        setUnavailable(true);
        return;
      }
      setGrants(
        result.data.filter((grant) => grant.connectorId === connectorId),
      );
      setUnavailable(false);
    });
    return () => {
      stale = true;
    };
  }, [connectorId, refreshKey, reloads]);

  if (grants.length === 0 && !unavailable) return null;

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
      {unavailable ? (
        <>
          <Notice variant="warning" data-testid="connector-grants-unavailable">
            허락 상태를 확인하지 못했어요.
          </Notice>
          <Button
            size="sm"
            variant="outline"
            data-testid="connector-grants-retry"
            onClick={() => setReloads((count) => count + 1)}
          >
            다시 확인
          </Button>
        </>
      ) : null}
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
