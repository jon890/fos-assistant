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
  readAdminConnections,
  type AdminConnection,
  type ConnectorSummary,
} from "@/lib/connection";

/** 바인딩 한 줄의 열쇠다. 한 에이전트에 같은 플러그인은 하나만 붙는다. */
const key = (connection: AdminConnection) =>
  `${connection.agentCode}/${connection.connectorId}`;

/**
 * 반영 실패, 자동 반영 중, 그 밖(선언하지 않은 도구만 남은 바인딩)으로 나눠 상태 글을 낸다.
 * 반영은 재시작 대기를 포함해 모두 Control Plane 이 스스로 한다. 자동 반영이 실패했거나 대기가 24시간을 넘은 줄만
 * 관리자가 「다시 확인」 으로 다시 맞춘다(`recheckable`).
 * 바인딩의 `READY` 는 에이전트에 붙었다는 뜻이라 연결 상태의 「연결됨」 이 아니라 「붙음」 이다.
 */
function statusText(connection: AdminConnection): string {
  if (connection.applyFailed) return "반영 실패";
  if (connection.restartRequired || connection.status === "PENDING")
    return "자동 반영 중";
  return "붙음";
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

  /** 다시 확인이다. 성공하든 거절되든 목록을 다시 읽어 바뀐 대기 시각과 상태를 받는다. */
  async function confirm(connection: AdminConnection) {
    if (pending !== null) return;
    setPending(key(connection));
    setError(null);
    const result = await confirmAdminConnection(connection);
    await reload();
    setPending(null);
    if (result.ok) return;
    setError(
      // 외부 호출 실패만 일반 문구로 두고, 나머지는 까닭별 문구를 보인다. 문구는 오류 표가 갖는다.
      result.code === "CONNECTOR_OPERATION_FAILED"
        ? "반영 상태를 확인하지 못했어요. 잠시 뒤 다시 해 주세요."
        : result.message,
    );
  }

  return (
    <Card className="mt-8" data-testid="connector-admin-panel">
      <CardHeader>
        <CardTitle>연결 반영 확인</CardTitle>
        <CardDescription>
          연결은 자동으로 반영돼요. 「다시 확인」 은 자동 반영이 실패했을 때만
          누르세요.
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
                {connection.recheckable ? (
                  <div className="flex flex-col items-end gap-1">
                    <Button
                      disabled={pending !== null}
                      variant="outline"
                      onClick={() => void confirm(connection)}
                      loading={pending === key(connection)}
                      loadingText="확인 중…"
                    >
                      다시 확인
                    </Button>
                    <span className="text-xs text-muted-foreground">
                      {connection.applyFailed
                        ? "자동 반영이 실패했어요."
                        : "하루 넘게 자동 반영이 끝나지 않았어요."}
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
