"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { SecretConfirmDialog } from "@/components/admin/secret-confirm-dialog";
import { formatWhen } from "@/lib/format";
import {
  needsAppSlug,
  oauthAppTitle,
  type OAuthAppInput,
  type OAuthAppSecret,
} from "@/lib/connector-oauth-admin-api";

/**
 * OAuth 앱 한 줄이다(단추 연결 스펙 REQ2). client ID 와 App 이름은 원문을, client secret 은 입력됨과 앞부분만 보인다.
 * 저장할 때마다 client secret 을 다시 넣는다. 저장한 뒤 그 칸은 비운다.
 */
export function OAuthAppRow({
  app,
  storeAvailable,
  busy,
  onSave,
  onClear,
}: {
  app: OAuthAppSecret;
  storeAvailable: boolean;
  busy: boolean;
  onSave(input: OAuthAppInput): Promise<void>;
  onClear(): Promise<void>;
}) {
  const slugNeeded = needsAppSlug(app.provider);
  const [clientId, setClientId] = useState(app.clientId ?? "");
  const [appSlug, setAppSlug] = useState(app.appSlug ?? "");
  const [clientSecret, setClientSecret] = useState("");
  const [confirming, setConfirming] = useState(false);
  const title = oauthAppTitle(app);
  const filled =
    clientId.trim() && clientSecret.trim() && (!slugNeeded || appSlug.trim());

  async function save() {
    await onSave({
      clientId: clientId.trim(),
      clientSecret: clientSecret.trim(),
      appSlug: slugNeeded ? appSlug.trim() : null,
    });
    // 저장했든 못 했든 비밀 원문은 화면에 남기지 않는다. client ID 와 App 이름은 고쳐 다시 보내도록 둔다.
    setClientSecret("");
  }

  return (
    <section
      data-testid={`secret-oauth-app-${app.connectorId}`}
      className="space-y-3 rounded-md border border-border p-3"
    >
      <div className="min-w-0 space-y-1 text-sm">
        <div className="flex flex-wrap items-center gap-2">
          <p className="font-medium">{title}</p>
          <Badge variant={app.set ? "success" : "outline"}>
            {app.set ? "입력됨" : "비어 있음"}
          </Badge>
          {app.prefix ? (
            <code className="text-muted-foreground">{app.prefix}…</code>
          ) : null}
        </div>
        <p className="text-muted-foreground">
          사용자가 「외부 서비스 연결」 에서 단추로 연결할 때 쓰는 앱이에요.
          모두 넣으면 그 플러그인에 단추가 생겨요.
        </p>
        {app.clientId ? (
          <p className="break-all text-muted-foreground">
            client ID <code>{app.clientId}</code>
          </p>
        ) : null}
        {app.appSlug ? (
          <p className="break-all text-muted-foreground">
            App 이름 <code>{app.appSlug}</code>
          </p>
        ) : null}
        {app.updatedAt ? (
          <p className="text-muted-foreground">
            {`마지막 변경 ${formatWhen(app.updatedAt)}`}
            {app.updatedBy
              ? ` · ${app.updatedBy.name ?? "알 수 없는 사용자"}`
              : ""}
          </p>
        ) : null}
      </div>
      <form
        className="flex flex-wrap items-center gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          if (filled && !busy) void save();
        }}
      >
        <Input
          aria-label={`${title} client ID`}
          placeholder="client ID"
          autoComplete="off"
          value={clientId}
          onChange={(event) => setClientId(event.target.value)}
          className="max-w-xs"
          disabled={busy || !storeAvailable}
        />
        {slugNeeded ? (
          <Input
            aria-label={`${title} App 이름`}
            placeholder="App 이름(slug)"
            autoComplete="off"
            value={appSlug}
            onChange={(event) => setAppSlug(event.target.value)}
            className="max-w-xs"
            disabled={busy || !storeAvailable}
          />
        ) : null}
        <Input
          aria-label={`${title} client secret`}
          placeholder="client secret"
          type="password"
          autoComplete="off"
          value={clientSecret}
          onChange={(event) => setClientSecret(event.target.value)}
          className="max-w-xs"
          disabled={busy || !storeAvailable}
        />
        <Button
          type="submit"
          size="sm"
          variant="outline"
          disabled={busy || !storeAvailable || !filled}
        >
          저장
        </Button>
        {app.set ? (
          <Button
            type="button"
            size="sm"
            variant="destructive"
            disabled={busy || !storeAvailable}
            onClick={() => setConfirming(true)}
          >
            지우기
          </Button>
        ) : null}
      </form>
      {confirming ? (
        <SecretConfirmDialog
          title={`${title} 설정을 지울까요?`}
          description="지우면 이 앱을 쓰는 플러그인의 단추가 사라지고, 단추로 만든 연결은 토큰을 새로 받지 못해요."
          confirmLabel="지우기"
          busyLabel="지우는 중…"
          destructive
          busy={busy}
          onCancel={() => setConfirming(false)}
          onConfirm={() => void onClear().then(() => setConfirming(false))}
        />
      ) : null}
    </section>
  );
}
