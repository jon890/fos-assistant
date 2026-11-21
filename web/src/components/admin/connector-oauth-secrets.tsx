"use client";

import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { describeAdminError } from "@/components/error-message";
import { OAuthAppRow } from "@/components/admin/oauth-app-row";
import { SecretConfirmDialog } from "@/components/admin/secret-confirm-dialog";
import { formatWhen } from "@/lib/format";
import {
  clearOAuthApp,
  fetchOAuthApps,
  fetchOAuthSigning,
  regenerateOAuthSigning,
  rewriteSummary,
  saveOAuthApp,
  signingConfirmText,
  type OAuthAppInput,
  type OAuthAppSecrets,
  type OAuthSigningRegenerated,
  type OAuthSigningSecret,
} from "@/lib/connector-oauth-admin-api";

async function readJson<T>(call: () => Promise<Response>): Promise<T | null> {
  try {
    const response = await call();
    return response.ok ? ((await response.json()) as T) : null;
  } catch {
    return null;
  }
}

async function failureOf(response: Response): Promise<string> {
  const payload = (await response.json().catch(() => ({}))) as {
    code?: string;
    message?: string;
  };
  return describeAdminError(
    payload.code ?? "INTERNAL_ERROR",
    payload.message ?? "요청을 처리하지 못했어요.",
  );
}

type Loaded = {
  apps: OAuthAppSecrets | null;
  signing: OAuthSigningSecret | null;
};

/** 앱 줄과 서명 비밀을 함께 읽는다. 읽지 못한 쪽은 null 이다. */
async function readBoth(): Promise<Loaded> {
  const [apps, signing] = await Promise.all([
    readJson<OAuthAppSecrets>(fetchOAuthApps),
    readJson<OAuthSigningSecret>(fetchOAuthSigning),
  ]);
  return { apps, signing };
}

/** 단추 연결 서명 비밀 한 줄이다. 서버가 만들고 관리하며 사람이 넣지 않는다. */
function SigningRow({
  signing,
  busy,
  onRegenerate,
}: {
  signing: OAuthSigningSecret;
  busy: boolean;
  onRegenerate(): void;
}) {
  return (
    <section
      data-testid="secret-oauth-signing"
      className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-border p-3"
    >
      <div className="min-w-0 space-y-1 text-sm">
        <div className="flex flex-wrap items-center gap-2">
          <p className="font-medium">단추 연결 서명 비밀</p>
          <Badge variant="outline">자동 관리</Badge>
          <Badge variant={signing.set ? "success" : "outline"}>
            {signing.set ? "만들어짐" : "아직 없음"}
          </Badge>
        </div>
        <p className="text-muted-foreground">
          단추 연결의 요청과 플러그인이 토큰을 받아 가는 주소를 서명해요. 처음
          연결할 때 서버가 만들고 다시 보이지 않아요.
        </p>
        {signing.updatedAt ? (
          <p className="text-muted-foreground">
            {`마지막 변경 ${formatWhen(signing.updatedAt)}`}
            {signing.updatedBy
              ? ` · ${signing.updatedBy.name ?? "알 수 없는 사용자"}`
              : ""}
          </p>
        ) : null}
        {signing.passageConfigured ? null : (
          <p className="text-muted-foreground">
            토큰 통로 주소를 만들 설정이 없어 단추 연결이 꺼져 있어요.
          </p>
        )}
      </div>
      <Button
        size="sm"
        variant="outline"
        disabled={busy || !signing.storeAvailable}
        onClick={onRegenerate}
      >
        새로 만들기
      </Button>
    </section>
  );
}

/**
 * 「서버 비밀값」 화면의 단추 연결 줄들이다(단추 연결 스펙 REQ2). 카탈로그가 OAuth 를 선언한 플러그인마다 앱 줄이 생긴다.
 * 서버 비밀값 목록과 따로 읽어, 이 줄을 읽지 못해도 다른 비밀값 줄은 그대로 보인다.
 */
export function ConnectorOAuthSecrets() {
  const [apps, setApps] = useState<OAuthAppSecrets | null>(null);
  const [signing, setSigning] = useState<OAuthSigningSecret | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<{
    lines: string[];
    warn: boolean;
  } | null>(null);

  function apply(next: Loaded) {
    if (next.apps) setApps(next.apps);
    if (next.signing) setSigning(next.signing);
    setLoadFailed(!next.apps || !next.signing);
  }

  const reload = async () => apply(await readBoth());

  useEffect(() => {
    void readBoth().then(apply);
  }, []);

  async function run(
    call: () => Promise<Response>,
    onOk?: (response: Response) => Promise<void>,
  ) {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const response = await call();
      if (!response.ok) setError(await failureOf(response));
      else if (onOk) await onOk(response);
    } catch {
      setError("요청을 보내지 못했어요. 잠시 뒤 다시 해 주세요.");
    }
    await reload();
    setBusy(false);
  }

  const save = (connectorId: string, input: OAuthAppInput) =>
    run(
      () => saveOAuthApp(connectorId, input),
      async () => setResult({ lines: ["앱 설정을 저장했어요."], warn: false }),
    );

  const regenerate = () =>
    run(regenerateOAuthSigning, async (response) => {
      const body = (await response.json()) as OAuthSigningRegenerated;
      setResult(rewriteSummary(body.rewrite));
    }).then(() => setConfirming(false));

  if (apps === null && signing === null) {
    return loadFailed ? (
      <Notice variant="error" role="alert">
        단추 연결 설정을 읽지 못했어요. 화면을 다시 열어 주세요.
      </Notice>
    ) : null;
  }

  return (
    <div className="space-y-3" data-testid="secret-oauth">
      <h2 className="pt-2 text-base font-semibold">단추 연결</h2>
      {apps?.apps.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          단추 연결을 선언한 플러그인이 없어요.
        </p>
      ) : null}
      {apps?.apps.map((app) => (
        <OAuthAppRow
          key={`${app.connectorId}:${app.updatedAt ?? ""}`}
          app={app}
          storeAvailable={apps.storeAvailable}
          busy={busy}
          onSave={(input) => save(app.connectorId, input)}
          onClear={() => run(() => clearOAuthApp(app.connectorId))}
        />
      ))}
      {signing ? (
        <SigningRow
          signing={signing}
          busy={busy}
          onRegenerate={() =>
            // 다시 쓸 단추 연결이 없으면 영향이 없어 확인 창 없이 만든다.
            signingConfirmText(signing.connectionCount) === null
              ? void regenerate()
              : setConfirming(true)
          }
        />
      ) : null}
      {loadFailed ? (
        <Notice variant="error" role="alert">
          단추 연결 설정 일부를 읽지 못했어요. 화면을 다시 열어 주세요.
        </Notice>
      ) : null}
      {result ? (
        <Notice variant={result.warn ? "warning" : "info"} role="status">
          {result.lines.map((line) => (
            <p key={line}>{line}</p>
          ))}
        </Notice>
      ) : null}
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
      {confirming && signing ? (
        <SecretConfirmDialog
          title="단추 연결 서명 비밀을 새로 만들까요?"
          description={signingConfirmText(signing.connectionCount) ?? ""}
          confirmLabel="새로 만들기"
          busyLabel="만드는 중…"
          destructive={false}
          busy={busy}
          onCancel={() => setConfirming(false)}
          onConfirm={() => void regenerate()}
        />
      ) : null}
    </div>
  );
}
