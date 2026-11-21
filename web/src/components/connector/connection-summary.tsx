"use client";

import Link from "next/link";
import { ConnectionBadge } from "@/components/connector/connection-badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import type { ConnectorConnection, ConnectorField } from "@/lib/connection";
import { formatChecked } from "@/lib/connection-load";

export function ConnectorNotFound() {
  return (
    <div className="mx-auto w-full max-w-2xl" data-testid="connector-not-found">
      <h1 className="mb-4 text-xl font-semibold">찾을 수 없는 서비스예요</h1>
      <Link
        href="/connections"
        className="text-sm text-foreground underline underline-offset-4"
      >
        서비스 연결로 돌아가기
      </Link>
    </div>
  );
}

export function ConnectionLoadFailed({
  message,
  onRetry,
}: {
  message: string;
  onRetry(): void;
}) {
  return (
    <div className="mx-auto w-full max-w-2xl space-y-3">
      <Notice variant="error" role="alert">
        연결 상태를 읽지 못했어요. {message}
      </Notice>
      <Button variant="outline" onClick={onRetry}>
        상태 다시 읽기
      </Button>
    </div>
  );
}

/**
 * 연결 상태와 저장된 칸 값, 연결 방식, 마지막 확인 시각이다. 비밀 칸은 앞부분만 보인다.
 * `hiddenKeys` 는 단추로 연결해 값이 서비스 쪽에만 있는 칸이다.
 */
export function ConnectionSummary({
  fields,
  connection,
  hiddenKeys,
  method,
}: {
  fields: ConnectorField[];
  connection: ConnectorConnection;
  hiddenKeys: Set<string>;
  /** 단추 연결을 선언한 플러그인의 연결 방식 표시다. 없으면 보이지 않는다. */
  method: string | null;
}) {
  const status = connection.status;
  const shown = fields.flatMap((field) => {
    if (hiddenKeys.has(field.key)) return [];
    if (!field.secret) {
      const value = connection.values[field.key];
      return value ? [{ field, value }] : [];
    }
    const prefix = connection.secretPrefixes[field.key];
    if (prefix) return [{ field, value: prefix }];
    // 짧은 비밀은 앞부분이 없다. 필수 칸은 등록됐다면 반드시 값이 있으므로 입력된 사실만 알린다.
    // 선택 칸은 입력했는지 응답으로 알 수 없어 보이지 않는다.
    return status !== "DISCONNECTED" && field.required
      ? [{ field, value: "입력됨" }]
      : [];
  });
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-muted px-3 py-2 text-sm">
        <span>
          상태{" "}
          <ConnectionBadge status={status} data-testid="connection-status" />
        </span>
        {shown.map(({ field, value }) => (
          <span key={field.key} className="break-all">
            {field.label}: {value}
          </span>
        ))}
        {method && status !== "DISCONNECTED" ? (
          <span data-testid="connection-method">{method}</span>
        ) : null}
      </div>
      {connection.checkedAt ? (
        <p className="text-sm text-muted-foreground">
          마지막 확인: {formatChecked(connection.checkedAt)}
        </p>
      ) : null}
    </>
  );
}
