"use client";

import { useEffect, useState } from "react";
import {
  ConnectionLoadFailed,
  ConnectionLoading,
  ConnectionSummary,
} from "@/components/connector/connection-summary";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { ErrorNotice, LoginNotice } from "@/components/connector/browser-login";
import { ConnectorOnboarding } from "@/components/agent/connector-onboarding";
import { BoundAgents } from "@/components/connector/connector-bound-agents";
import { ConnectorHeading } from "@/components/connector/connector-identity";
import { ConnectorLimitSections } from "@/components/connector/connector-limit-sections";
import { ConnectorGrants } from "@/components/connector/connector-grants";
import { ConnectorToolsSection } from "@/components/connector/connector-tools";
import { useConnectionPoll } from "@/components/connector/use-binding-poll";
import { ConnectorFieldInput } from "@/components/connector/connector-field-input";
import {
  OAuthConnect,
  OAuthNotices,
  oauthStartValues,
  useOAuthNotices,
  oauthMethodLabel,
} from "@/components/connector/connector-oauth";
import { Notice } from "@/components/ui/notice";
import { PageFrame } from "@/components/ui/page-frame";
import { CONNECTION_BACK, ConnectorNotFound } from "./connector-not-found";
import {
  CONNECTOR_ID_PATTERN,
  checkConnection,
  disconnectConnection,
  readConnection,
  readOptions,
  registerConnection,
  type ConnectionFailure,
  type ConnectorConnection,
  type ConnectorField,
  type ConnectorOption,
} from "@/lib/connection";
import {
  safeAuth,
  startOAuth,
  type OAuthOutcome,
} from "@/lib/connection-oauth";
import { fetchLoaded, type Loaded } from "@/lib/connection-load";

type Pending =
  "save" | "check" | "disconnect" | "oauth" | `options:${string}` | null;

/**
 * 에이전트 고르기 영역의 상태다. `auto` 는 연결됐고 아직 쓰는 에이전트가 없을 때만 보인다.
 * 연결을 막 마쳤거나 사용자가 열었으면 `open` 이고, 붙인 뒤에도 결과를 보이려고 닫을 때까지 둔다.
 */
type Chooser = "auto" | "open" | "closed";

export function ConnectorConnectionPanel({
  id,
  preferredAgent,
  admin,
  oauthResult,
}: {
  id: string;
  /** 에이전트 화면에서 이 연결을 하러 왔으면 그 에이전트 번호다. 고르기 영역이 맨 앞에 둔다. */
  preferredAgent: string | null;
  /** 관리자에게만 단추 연결 설정 안내를 보인다. */
  admin: boolean;
  /** 서비스 화면에서 돌아온 결과다. 돌아오는 화면이 주소 검색어로 넘긴다. */
  oauthResult: { outcome: OAuthOutcome; reason: string | null } | null;
}) {
  const [loaded, setLoaded] = useState<Loaded>({ kind: "loading" });
  const notices = useOAuthNotices(id, oauthResult, preferredAgent);
  const [values, setValues] = useState(notices.restored);
  const [options, setOptions] = useState<Record<string, ConnectorOption[]>>({});
  const [pending, setPending] = useState<Pending>(null);
  const [error, setError] = useState<string | ConnectionFailure | null>(null);
  // 단추 연결을 마치고 돌아왔으면 바로 쓸 에이전트를 고르게 한다.
  const back = oauthResult?.outcome === "CONNECTED";
  const [chooser, setChooser] = useState<Chooser>(back ? "open" : "auto");
  const [justConnected, setJustConnected] = useState(back);
  const busy = pending !== null;

  const validId = CONNECTOR_ID_PATTERN.test(id);

  useEffect(() => {
    // 형식이 틀린 id 로는 서버를 부르지 않는다.
    if (validId) void fetchLoaded(id).then(setLoaded);
  }, [id, validId]);

  useConnectionPoll(id, validId, loaded, busy, setLoaded);

  if (!validId || loaded.kind === "notFound") return <ConnectorNotFound />;
  if (loaded.kind === "loading") return <ConnectionLoading />;
  if (loaded.kind === "failed")
    return (
      <ConnectionLoadFailed id={id} failed={loaded} onLoaded={setLoaded} />
    );

  const { connector, connection } = loaded;
  const available = connector?.available === true;
  const fields = available ? connector.fields : [];
  const oauth = available ? (connector.oauth ?? null) : null;
  const auth = safeAuth(connection.auth);
  // 단추를 쓸 수 있으면 단추가 대신하는 칸을 「직접 입력」 아래 접는다.
  const folded = new Set(oauth?.ready ? oauth.fields : []);
  const oauthKeys = new Set(
    auth.method === "oauth" ? (oauth?.fields ?? []) : [],
  );
  const title = connector?.title ?? id;
  const loginUrl = connector?.ownerBrowserLoginUrl;
  const secretsFilled = fields
    .filter((field) => field.secret)
    .every((field) => values[field.key]?.trim());
  const complete = fields.every(
    (field) => !field.required || values[field.key]?.trim(),
  );
  const buttonFields = fields.filter((field) => !folded.has(field.key));
  const startValues = oauthStartValues(buttonFields, values, connection);
  const canStart = buttonFields.every(
    (field) => !field.required || startValues[field.key],
  );

  /** 단추 밖 칸 값만 보내고 받은 로그인 주소로 이 창을 보낸다. */
  async function connectWithOAuth() {
    if (busy || !canStart) return;
    setPending("oauth");
    setError(null);
    notices.setReturned(null);
    notices.setRevoke(null);
    notices.remember(buttonFields, startValues);
    const started = await startOAuth(id, startValues);
    if (!started.ok) {
      setPending(null);
      return setError(started);
    }
    window.location.assign(started.data.authorizeUrl);
  }

  function setField(field: ConnectorField, value: string) {
    setValues((current) => {
      const next = { ...current, [field.key]: value };
      // 비밀 칸이 바뀌면 그 값으로 불러온 선택지와 고른 값이 낡는다.
      if (field.secret) {
        for (const other of fields) if (other.hasOptions) next[other.key] = "";
      }
      return next;
    });
    if (field.secret) setOptions({});
  }

  /** 선택지를 부르는 요청에는 선택지 칸을 뺀 입력값만 싣는다. */
  function inputValues(): Record<string, string> {
    const result: Record<string, string> = {};
    for (const field of fields) {
      const value = values[field.key]?.trim();
      if (value && !field.hasOptions) result[field.key] = value;
    }
    return result;
  }

  async function loadOptions(field: ConnectorField) {
    if (busy || !secretsFilled) return;
    setPending(`options:${field.key}`);
    setError(null);
    const result = await readOptions(id, field.key, inputValues());
    setPending(null);
    if (!result.ok) {
      setValues((current) => {
        const next = { ...current };
        for (const other of fields)
          if (other.secret || other.hasOptions) next[other.key] = "";
        return next;
      });
      setOptions({});
      setError(result.message);
      return;
    }
    setOptions((current) => ({ ...current, [field.key]: result.data }));
    setValues((current) => ({
      ...current,
      [field.key]:
        field.autoSelectSingle && result.data.length === 1
          ? result.data[0].value
          : "",
    }));
    if (result.data.length === 0)
      setError("고를 수 있는 항목이 없어요. 입력한 값을 확인해 주세요.");
  }

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || !complete) return;
    const submitted: Record<string, string> = {};
    for (const field of fields) {
      const value = values[field.key]?.trim();
      if (value) submitted[field.key] = value;
    }
    // 비밀 원문은 보낸 직후 화면에서 비운다.
    setValues((current) => {
      const next = { ...current };
      for (const field of fields)
        if (field.secret || field.hasOptions) next[field.key] = "";
      return next;
    });
    setOptions({});
    setPending("save");
    setError(null);
    const result = await registerConnection(id, submitted);
    setPending(null);
    if (!result.ok) return setError(result);
    showConnected(result.data);
  }

  /** 새 연결 상태를 그린다. 이번에 처음 연결됐으면 같은 자리에서 쓸 에이전트를 고르게 한다. */
  function showConnected(next: ConnectorConnection) {
    if (next.status === "READY" && connection.status !== "READY") {
      setJustConnected(true);
      setChooser("open");
    }
    setLoaded({ kind: "ready", connector, connection: next });
  }

  async function refreshBindings() {
    // 붙인 결과를 보이는 동안 영역이 닫히지 않게 연 채로 둔다.
    setChooser("open");
    const fresh = await readConnection(id);
    if (fresh.ok)
      setLoaded({ kind: "ready", connector, connection: fresh.data });
  }

  async function act(kind: "check" | "disconnect") {
    if (busy) return;
    setPending(kind);
    setError(null);
    notices.setReturned(null);
    notices.setRevoke(null);
    const result = await (kind === "check"
      ? checkConnection(id)
      : disconnectConnection(id));
    if (
      !result.ok &&
      kind === "disconnect" &&
      result.code === "CONNECTOR_OPERATION_FAILED"
    ) {
      // 붙은 에이전트에서 떼다 멈춘 해제는 연결을 준비 중으로 남긴다. 다시 누르면 남은 것부터 이어서 뗀다.
      const fresh = await readConnection(id);
      setPending(null);
      if (fresh.ok) {
        setLoaded({ kind: "ready", connector, connection: fresh.data });
        if (fresh.data.status === "PENDING") {
          return setError("해제가 끝나지 않았어요. 다시 해제를 눌러 주세요.");
        }
      }
      return setError(result.message);
    }
    if (!result.ok && kind === "check") {
      // 확인이 실패하면 서버가 연결을 준비 중으로 저장할 수 있으므로 오류 코드와 관계없이 상태를 다시 읽는다.
      const fresh = await readConnection(id);
      setPending(null);
      if (fresh.ok) {
        setLoaded({ kind: "ready", connector, connection: fresh.data });
      }
      return setError(
        result.code === "CONNECTOR_NOT_CONNECTED"
          ? "값을 다시 입력해 연결해 주세요."
          : result,
      );
    }
    setPending(null);
    if (!result.ok) return setError(result.message);
    if (kind === "check") showConnected(result.data);
    else {
      notices.setRevoke(result.data.oauthRevoke ?? null);
      setLoaded({ kind: "ready", connector, connection: result.data });
    }
  }

  const status = connection.status;
  const canChoose = available && status === "READY";
  const preferredUnbound =
    preferredAgent !== null &&
    !connection.bindings.some(
      (binding) => binding.agentCode === preferredAgent,
    );
  const chooserShown =
    canChoose &&
    (chooser === "open" ||
      (chooser === "auto" &&
        (connection.bindings.length === 0 || preferredUnbound)));
  const renderField = (field: ConnectorField) => (
    <ConnectorFieldInput
      key={field.key}
      field={field}
      value={values[field.key] ?? ""}
      options={options[field.key]}
      busy={busy}
      loading={pending === `options:${field.key}`}
      canLoad={secretsFilled}
      onChange={(value) => setField(field, value)}
      onLoadOptions={() => void loadOptions(field)}
    />
  );
  const manualSubmit = (
    <Button
      type="submit"
      disabled={busy || !complete}
      loading={pending === "save"}
      loadingText="연결하는 중…"
    >
      {status === "DISCONNECTED" ? "연결하기" : "값 바꾸기"}
    </Button>
  );

  return (
    <PageFrame title={title} back={CONNECTION_BACK}>
      {chooserShown ? (
        <ConnectorOnboarding
          connectorId={id}
          title={title}
          bindings={connection.bindings}
          justConnected={justConnected}
          preferredAgent={preferredAgent}
          onBindingsChanged={() => void refreshBindings()}
          onCreated={() => setChooser("open")}
          onClose={() => {
            setChooser("closed");
            setJustConnected(false);
          }}
        />
      ) : null}
      <Card>
        <CardHeader>
          <ConnectorHeading connector={connector} title={title} />
        </CardHeader>
        <CardContent className="space-y-5">
          <ConnectionSummary
            fields={fields}
            connection={connection}
            hiddenKeys={oauthKeys}
            method={oauth ? oauthMethodLabel(auth) : null}
          />
          <OAuthNotices
            returned={notices.returned}
            revoke={notices.revoke}
            provider={oauth?.provider ?? auth.provider}
            canRetry={oauth?.ready === true}
            busy={busy || !canStart}
            onRetry={() => void connectWithOAuth()}
          />
          {!available ? (
            <Notice variant="info" role="status">
              지금은 쓸 수 없어요. 연결을 해제할 수만 있어요.
            </Notice>
          ) : null}
          {status !== "DISCONNECTED" ? (
            <BoundAgents
              bindings={connection.bindings}
              onChoose={
                canChoose && !chooserShown ? () => setChooser("open") : null
              }
            />
          ) : null}
          {available ? (
            <form onSubmit={save} className="space-y-3">
              <LoginNotice url={loginUrl} />
              {buttonFields.map(renderField)}
              {oauth ? (
                <OAuthConnect
                  oauth={oauth}
                  auth={auth}
                  status={status}
                  admin={admin}
                  busy={busy}
                  starting={pending === "oauth"}
                  canStart={canStart}
                  onStart={() => void connectWithOAuth()}
                />
              ) : null}
              {folded.size > 0 ? (
                <details className="space-y-3" data-testid="manual-entry">
                  <summary className="cursor-pointer text-sm text-muted-foreground">
                    직접 입력
                  </summary>
                  {fields
                    .filter((field) => folded.has(field.key))
                    .map(renderField)}
                  {manualSubmit}
                </details>
              ) : (
                manualSubmit
              )}
            </form>
          ) : null}
          {available && status !== "DISCONNECTED" ? (
            <Button
              disabled={busy}
              variant="outline"
              onClick={() => void act("check")}
              loading={pending === "check"}
              loadingText="확인하는 중…"
            >
              연결 다시 확인
            </Button>
          ) : null}
          <ConnectorToolsSection
            tools={connector?.tools ?? []}
            undeclaredTools={connection.undeclaredTools}
            standard={connector?.standardPlugin ? connection : undefined}
          />
          <ConnectorGrants connectorId={id} refreshKey={connection} />
          <ConnectorLimitSections tools={connector?.tools ?? []} />
          {status !== "DISCONNECTED" ? (
            <Button
              disabled={busy}
              variant="destructive"
              onClick={() => void act("disconnect")}
              loading={pending === "disconnect"}
              loadingText="해제하는 중…"
            >
              연결 해제
            </Button>
          ) : null}
          <ErrorNotice error={error} loginUrl={loginUrl} />
        </CardContent>
      </Card>
    </PageFrame>
  );
}
