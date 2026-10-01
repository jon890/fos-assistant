"use client";

import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  confirmAdminConnection,
  connectionStatusLabel,
  readAdminConnections,
  type AdminConnection,
  type ConnectorSummary,
} from "@/lib/connection";

const key = (connection: AdminConnection) =>
  `${connection.connectorId}/${connection.userId}`;

/** 준비 중, 재시작 대기, 그 밖(선언하지 않은 도구만 남은 연결)으로 나눠 상태 글을 낸다. */
function statusText(connection: AdminConnection): string {
  if (connection.status === "PENDING") return "준비 중";
  if (connection.restartRequired) return "연결 해제";
  return connectionStatusLabel(connection.status);
}

export function ConnectorAdminPanel({
  connectors,
}: {
  connectors: ConnectorSummary[];
}) {
  const [connections, setConnections] = useState<AdminConnection[]>([]);
  const [pending, setPending] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const waiting = connections.filter(
    (connection) =>
      connection.restartRequired ||
      connection.status === "PENDING" ||
      connection.undeclaredTools > 0,
  );
  const titleOf = (id: string) =>
    connectors.find((connector) => connector.id === id)?.title ?? id;

  useEffect(() => {
    void readAdminConnections().then((result) => {
      if (result.ok) setConnections(result.data);
      else setError("연결 목록을 읽지 못했어요. 화면을 다시 열어 주세요.");
    });
  }, []);

  async function confirm(connection: AdminConnection) {
    if (pending !== null) return;
    setPending(key(connection));
    setError(null);
    const result = await confirmAdminConnection(
      connection.connectorId,
      connection.userId,
    );
    setPending(null);
    if (
      !result.ok ||
      result.data.userId !== connection.userId ||
      result.data.restartRequired !== false ||
      result.data.status === "PENDING"
    ) {
      setError("반영 상태를 확인하지 못했어요. 잠시 뒤 다시 해 주세요.");
      return;
    }
    const next = result.data;
    setConnections((items) =>
      items.map((item) =>
        key(item) === key(connection)
          ? { ...item, ...next, displayName: item.displayName }
          : item,
      ),
    );
  }

  return (
    <Card className="mt-8" data-testid="connector-admin-panel">
      <CardHeader>
        <CardTitle>연결 반영 확인</CardTitle>
        <CardDescription>
          공유 gateway 를 재시작한 뒤 사용자별 연결을 확인해요.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {waiting.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            반영 확인이 필요한 연결이 없어요.
          </p>
        ) : (
          <ul className="space-y-2">
            {waiting.map((connection) => (
              <li
                key={key(connection)}
                className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-border p-3"
              >
                <div className="text-sm">
                  <p className="font-medium">
                    {connection.displayName ?? "이름 없는 사용자"} ·{" "}
                    {titleOf(connection.connectorId)}
                  </p>
                  <p className="text-muted-foreground">
                    {statusText(connection)}
                  </p>
                  {connection.undeclaredTools > 0 ? (
                    <Badge
                      variant="destructive"
                      data-testid="admin-undeclared"
                      className="mt-1"
                    >
                      선언하지 않은 도구 {connection.undeclaredTools}개
                    </Badge>
                  ) : null}
                </div>
                {connection.restartRequired ||
                connection.status === "PENDING" ? (
                  <div className="flex flex-col items-end gap-1">
                    <Button
                      disabled={pending !== null}
                      variant="outline"
                      onClick={() => void confirm(connection)}
                      loading={pending === key(connection)}
                      loadingText="확인 중…"
                    >
                      반영 완료 확인
                    </Button>
                    <span className="text-xs text-muted-foreground">
                      공유 gateway 를 재시작한 뒤 눌러 주세요.
                    </span>
                  </div>
                ) : null}
              </li>
            ))}
          </ul>
        )}
        {error ? (
          <Notice variant="error" role="alert">
            {error}
          </Notice>
        ) : null}
      </CardContent>
    </Card>
  );
}
