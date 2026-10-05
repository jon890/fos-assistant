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

/** 바인딩 한 줄의 열쇠다. 한 에이전트에 같은 커넥터는 하나만 붙는다. */
const key = (connection: AdminConnection) =>
  `${connection.agentCode}/${connection.connectorId}`;

/** 재시작 대기, 준비 중, 그 밖(선언하지 않은 도구만 남은 바인딩)으로 나눠 상태 글을 낸다. */
function statusText(connection: AdminConnection): string {
  if (connection.restartRequired) return "반영 대기";
  if (connection.status === "PENDING") return "준비 중";
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

  async function reload() {
    const result = await readAdminConnections();
    if (result.ok) setConnections(result.data);
  }

  useEffect(() => {
    void readAdminConnections().then((result) => {
      if (result.ok) setConnections(result.data);
      else setError("연결 목록을 읽지 못했어요. 화면을 다시 열어 주세요.");
    });
  }, []);

  /** 반영 완료다. 성공하든 거절되든 목록을 다시 읽어 바뀐 대기 시각과 상태를 받는다. */
  async function confirm(connection: AdminConnection) {
    if (pending !== null) return;
    setPending(key(connection));
    setError(null);
    const result = await confirmAdminConnection(connection);
    await reload();
    setPending(null);
    if (result.ok) return;
    setError(
      // 재시작한 뒤 다시 설치된 바인딩은 한 번 더 재시작해야 한다고 알린다. 문구는 오류 표가 갖는다.
      result.code === "CONNECTOR_RESTART_AGAIN"
        ? result.message
        : "반영 상태를 확인하지 못했어요. 잠시 뒤 다시 해 주세요.",
    );
  }

  return (
    <Card className="mt-8" data-testid="connector-admin-panel">
      <CardHeader>
        <CardTitle>연결 반영 확인</CardTitle>
        <CardDescription>
          공유 gateway 를 재시작한 뒤 에이전트마다 붙인 연결을 확인해요.
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
                <div className="min-w-0 text-sm" data-testid="admin-binding">
                  <p className="font-medium">
                    {connection.displayName ?? "이름 없는 사용자"} ·{" "}
                    {titleOf(connection.connectorId)}
                  </p>
                  <p className="break-all text-muted-foreground">
                    에이전트 {connection.agentCode}
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
                      반영 완료
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
