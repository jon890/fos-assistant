"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import type {
  ConnectionStatus,
  ConnectorConnection,
  ConnectorField,
} from "@/lib/connection";
import {
  oauthOutcomeMessage,
  providerName,
  type ConnectionAuth,
  type ConnectionOAuthRevoke,
  type ConnectorOAuth,
  type OAuthOutcome,
} from "@/lib/connection-oauth";

const LINK = "text-sm text-foreground underline underline-offset-4";

function External({
  href,
  children,
}: {
  href: string;
  children: React.ReactNode;
}) {
  return (
    <a href={href} target="_blank" rel="noreferrer noopener" className={LINK}>
      {children}
    </a>
  );
}

/**
 * 단추 연결 자리다(스펙 REQ3, REQ7). 앱이 설정돼 있으면 단추를, 아니면 관리자에게만 서버 비밀값 안내를 보인다.
 * 단추 밖 칸은 이 위에, 단추가 대신하는 칸은 아래 「직접 입력」 에 접어 둔다. 그 배치는 연결 화면이 한다.
 */
export function OAuthConnect({
  oauth,
  auth,
  status,
  admin,
  busy,
  starting,
  canStart,
  onStart,
}: {
  oauth: ConnectorOAuth;
  auth: ConnectionAuth;
  status: ConnectionStatus;
  admin: boolean;
  busy: boolean;
  starting: boolean;
  /** 단추 밖 필수 칸이 모두 채워졌다. */
  canStart: boolean;
  onStart(): void;
}) {
  const name = providerName(oauth.provider);
  const reconnect = status !== "DISCONNECTED" && auth.method === "oauth";
  return (
    <div className="space-y-3" data-testid="oauth-connect">
      {auth.reconnectRequired ? (
        <Notice variant="warning" role="status">
          {`${name} 연결이 끊겼어요. 다시 연결해 주세요.`}
        </Notice>
      ) : null}
      {oauth.ready ? (
        <div className="flex flex-wrap items-center gap-3">
          <Button
            type="button"
            disabled={busy || !canStart}
            loading={starting}
            loadingText={`${name} 으로 가는 중…`}
            onClick={onStart}
          >
            {reconnect ? `${name} 으로 다시 연결` : `${name} 으로 연결`}
          </Button>
          {reconnect && oauth.installSettingsUrl ? (
            <External
              href={oauth.installSettingsUrl}
            >{`${name} 에서 저장소 바꾸기`}</External>
          ) : null}
        </div>
      ) : admin ? (
        <Notice variant="info" role="status">
          {`서버 비밀값에서 ${name} 앱을 설정하면 단추로 연결할 수 있어요. `}
          <Link href="/admin/secrets" className={LINK}>
            서버 비밀값 열기
          </Link>
        </Notice>
      ) : null}
    </div>
  );
}

/** 연결 방식 표시다(스펙 DECISION15). */
export function oauthMethodLabel(auth: ConnectionAuth): string {
  return auth.method === "oauth" ? "단추로 연결함" : "직접 입력함";
}

/** 해제 뒤 서비스 쪽 허락 취소 결과다(스펙 REQ8). 실패해도 연결은 해제됐다. */
export function OAuthRevokeNotice({
  revoke,
  provider,
}: {
  revoke: ConnectionOAuthRevoke;
  provider: string | null;
}) {
  const name = providerName(provider);
  return (
    <Notice
      variant={revoke.status === "failed" ? "warning" : "info"}
      role="status"
      data-testid="oauth-revoke"
    >
      {revoke.status === "failed" ? (
        <p>
          {`연결은 해제했지만 ${name} 쪽 허락을 거두지 못했어요. ${name} 설정에서 앱 허락을 직접 거둬 주세요. `}
          {revoke.settingsUrl ? (
            <External href={revoke.settingsUrl}>{`${name} 설정 열기`}</External>
          ) : null}
        </p>
      ) : (
        <p>{`${name} 쪽 허락도 거뒀어요.`}</p>
      )}
      {revoke.installSettingsUrl ? (
        <p>
          {`${name} 설정에서 앱 설치도 지울 수 있어요. `}
          <External href={revoke.installSettingsUrl}>
            앱 설치 화면 열기
          </External>
        </p>
      ) : null}
    </Notice>
  );
}

/** 서비스 화면에서 돌아온 결과다. 실패면 까닭과 「다시 시도」 를 보인다(스펙 REQ5, DECISION20). */
export function OAuthResultNotice({
  outcome,
  reason,
  provider,
  canRetry,
  busy,
  onRetry,
}: {
  outcome: OAuthOutcome;
  reason: string | null;
  provider: string | null;
  canRetry: boolean;
  busy: boolean;
  onRetry(): void;
}) {
  const message = oauthOutcomeMessage(outcome, reason);
  if (message === null) {
    return (
      <Notice variant="success" role="status" data-testid="oauth-result">
        {`${providerName(provider)} 으로 연결했어요.`}
      </Notice>
    );
  }
  return (
    <Notice variant="error" role="alert" data-testid="oauth-result">
      <p>{message}</p>
      {canRetry ? (
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="mt-2"
          disabled={busy}
          onClick={onRetry}
        >
          다시 시도
        </Button>
      ) : null}
    </Notice>
  );
}

/** 돌아온 결과와 해제 결과를 함께 보인다. 둘 다 없으면 그리지 않는다. */
export function OAuthNotices({
  returned,
  revoke,
  provider,
  canRetry,
  busy,
  onRetry,
}: {
  returned: { outcome: OAuthOutcome; reason: string | null } | null;
  revoke: ConnectionOAuthRevoke | null;
  provider: string | null;
  canRetry: boolean;
  busy: boolean;
  onRetry(): void;
}) {
  return (
    <>
      {returned ? (
        <OAuthResultNotice
          outcome={returned.outcome}
          reason={returned.reason}
          provider={provider}
          canRetry={canRetry}
          busy={busy}
          onRetry={onRetry}
        />
      ) : null}
      {revoke ? (
        <OAuthRevokeNotice revoke={revoke} provider={provider} />
      ) : null}
    </>
  );
}

/**
 * 돌아온 결과와 해제 결과의 상태다. 돌아온 결과는 다시 읽어도 또 보이지 않게 주소의 검색어를 지운다.
 * 주소만 바꾸고 화면은 다시 그리지 않는다.
 */
export function useOAuthNotices(
  id: string,
  oauthResult: { outcome: OAuthOutcome; reason: string | null } | null,
) {
  const [returned, setReturned] = useState(oauthResult);
  const [revoke, setRevoke] = useState<ConnectionOAuthRevoke | null>(null);
  useEffect(() => {
    if (oauthResult)
      window.history.replaceState(null, "", `/connections/${id}`);
  }, [id, oauthResult]);
  return { returned, setReturned, revoke, setRevoke };
}

/**
 * 단추가 보낼 단추 밖 칸 값이다. 입력한 값을 앞뒤 공백을 지워 쓰고, 입력하지 않은 비밀 아닌 칸은 저장된 값을 쓴다.
 * 다시 연결할 때 저장소 이름 같은 칸을 또 넣지 않게 한다. 비밀 칸은 저장된 원문이 없어 늘 새로 넣는다.
 */
export function oauthStartValues(
  fields: ConnectorField[],
  typed: Record<string, string>,
  connection: ConnectorConnection,
): Record<string, string> {
  const result: Record<string, string> = {};
  for (const field of fields) {
    const value =
      typed[field.key]?.trim() ||
      (field.secret ? "" : (connection.values[field.key] ?? ""));
    if (value) result[field.key] = value;
  }
  return result;
}
