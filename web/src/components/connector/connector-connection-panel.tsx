"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { ConnectorHeading } from "@/components/connector/connector-identity";
import { ConnectorGrants } from "@/components/connector/connector-grants";
import { ConnectorTools } from "@/components/connector/connector-tools";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Notice } from "@/components/ui/notice";
import {
  CONNECTOR_ID_PATTERN,
  checkConnection,
  connectionStatusLabel,
  disconnectConnection,
  readConnection,
  readConnectors,
  readOptions,
  registerConnection,
  type BoundAgent,
  type ConnectorConnection,
  type ConnectorField,
  type ConnectorOption,
  type ConnectorSummary,
} from "@/lib/connection";

type Loaded =
  | { kind: "loading" }
  | { kind: "notFound" }
  | { kind: "failed"; message: string }
  | {
      kind: "ready";
      connector: ConnectorSummary | null;
      connection: ConnectorConnection;
    };

type Pending = "save" | "check" | "disconnect" | `options:${string}` | null;

function formatChecked(value: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "Asia/Seoul",
  }).format(new Date(value));
}

async function fetchLoaded(id: string): Promise<Loaded> {
  const [catalog, connection] = await Promise.all([
    readConnectors(),
    readConnection(id),
  ]);
  if (!connection.ok) {
    return connection.code === "CONNECTOR_NOT_FOUND"
      ? { kind: "notFound" }
      : { kind: "failed", message: connection.message };
  }
  // 카탈로그를 읽지 못한 것을 목록에서 빠진 것으로 보이면 일시 장애에 해제만 남는다.
  if (!catalog.ok) return { kind: "failed", message: catalog.message };
  const connector = catalog.data.find((item) => item.id === id) ?? null;
  return { kind: "ready", connector, connection: connection.data };
}

/** 이 연결을 붙인 에이전트 목록이다. 붙이기와 떼기는 에이전트 화면에서 한다. */
function BoundAgents({ bindings }: { bindings: BoundAgent[] }) {
  return (
    <section className="space-y-2" data-testid="connection-bindings">
      <h2 className="text-sm font-semibold">붙인 에이전트</h2>
      {bindings.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          아직 이 연결을 쓰는 에이전트가 없어요. 에이전트 화면에서 붙여요.
        </p>
      ) : (
        <ul className="divide-y divide-border rounded-md border border-border">
          {bindings.map((binding) => (
            <li
              key={binding.agentCode}
              className="flex flex-wrap items-center justify-between gap-2 p-3 text-sm"
              data-testid="connection-binding"
            >
              <Link
                prefetch={false}
                href={`/agents/${binding.agentCode}`}
                className="min-w-0 break-all text-foreground underline underline-offset-4"
              >
                {binding.agentName}
              </Link>
              {binding.restartRequired || binding.status === "PENDING" ? (
                <Badge variant="warning">반영 대기</Badge>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function NotFound() {
  return (
    <div className="mx-auto w-full max-w-2xl" data-testid="connector-not-found">
      <h1 className="mb-4 text-xl font-semibold">찾을 수 없는 서비스예요</h1>
      <Link
        href="/connections"
        className="text-sm text-foreground underline underline-offset-4"
      >
        연결 목록으로 돌아가기
      </Link>
    </div>
  );
}

export function ConnectorConnectionPanel({ id }: { id: string }) {
  const [loaded, setLoaded] = useState<Loaded>({ kind: "loading" });
  const [values, setValues] = useState<Record<string, string>>({});
  const [options, setOptions] = useState<Record<string, ConnectorOption[]>>({});
  const [pending, setPending] = useState<Pending>(null);
  const [error, setError] = useState<string | null>(null);
  const busy = pending !== null;

  const validId = CONNECTOR_ID_PATTERN.test(id);

  useEffect(() => {
    // 형식이 틀린 id 로는 서버를 부르지 않는다.
    if (validId) void fetchLoaded(id).then(setLoaded);
  }, [id, validId]);

  if (!validId || loaded.kind === "notFound") return <NotFound />;
  if (loaded.kind === "loading")
    return <p className="text-sm text-muted-foreground">불러오는 중…</p>;
  if (loaded.kind === "failed") {
    return (
      <div className="mx-auto w-full max-w-2xl space-y-3">
        <Notice variant="error" role="alert">
          연결 상태를 읽지 못했어요. {loaded.message}
        </Notice>
        <Button
          variant="outline"
          onClick={() => void fetchLoaded(id).then(setLoaded)}
        >
          상태 다시 읽기
        </Button>
      </div>
    );
  }

  const { connector, connection } = loaded;
  const available = connector?.available === true;
  const fields = available ? connector.fields : [];
  const title = connector?.title ?? id;
  const secretsFilled = fields
    .filter((field) => field.secret)
    .every((field) => values[field.key]?.trim());
  const complete = fields.every(
    (field) => !field.required || values[field.key]?.trim(),
  );

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
    if (!result.ok) return setError(result.message);
    setLoaded({ kind: "ready", connector, connection: result.data });
  }

  async function act(kind: "check" | "disconnect") {
    if (busy) return;
    setPending(kind);
    setError(null);
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
    if (
      !result.ok &&
      kind === "check" &&
      result.code === "CONNECTOR_NOT_CONNECTED"
    ) {
      // 확인할 값이 남아 있지 않다. 서버가 연결을 준비 중으로 커밋했으므로 다시 읽어 상태를 맞춘다.
      // 이 화면이 연결 화면이므로 공통 문구 대신 값을 다시 넣으라고 알린다.
      const fresh = await readConnection(id);
      setPending(null);
      if (fresh.ok) {
        setLoaded({ kind: "ready", connector, connection: fresh.data });
      }
      return setError("값을 다시 입력해 연결해 주세요.");
    }
    setPending(null);
    if (!result.ok) return setError(result.message);
    setLoaded({ kind: "ready", connector, connection: result.data });
  }

  const status = connection.status;
  const shown = fields.flatMap((field) => {
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
    <div className="mx-auto w-full max-w-2xl">
      <Card>
        <CardHeader>
          <ConnectorHeading connector={connector} title={title} />
        </CardHeader>
        <CardContent className="space-y-5">
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-muted px-3 py-2 text-sm">
            <span>
              상태{" "}
              <Badge variant="outline" data-testid="connection-status">
                {connectionStatusLabel(status)}
              </Badge>
            </span>
            {shown.map(({ field, value }) => (
              <span key={field.key} className="break-all">
                {field.label}: {value}
              </span>
            ))}
          </div>
          {connection.checkedAt ? (
            <p className="text-sm text-muted-foreground">
              마지막 확인: {formatChecked(connection.checkedAt)}
            </p>
          ) : null}
          {!available ? (
            <Notice variant="info" role="status">
              지금은 쓸 수 없어요. 연결을 해제할 수만 있어요.
            </Notice>
          ) : null}
          {status !== "DISCONNECTED" ? (
            <BoundAgents bindings={connection.bindings} />
          ) : null}
          {available ? (
            <form onSubmit={save} className="space-y-3">
              {fields.map((field) => (
                <div key={field.key} className="space-y-2">
                  <Label htmlFor={`connector-field-${field.key}`}>
                    {field.label}
                  </Label>
                  {field.hasOptions ? (
                    <div className="flex items-center gap-2">
                      {options[field.key]?.length ? (
                        <NativeSelect
                          id={`connector-field-${field.key}`}
                          value={values[field.key] ?? ""}
                          disabled={busy}
                          onChange={(event) =>
                            setField(field, event.target.value)
                          }
                        >
                          <option value="">선택</option>
                          {options[field.key].map((option) => (
                            <option key={option.value} value={option.value}>
                              {option.label}
                            </option>
                          ))}
                        </NativeSelect>
                      ) : null}
                      <Button
                        type="button"
                        variant="outline"
                        disabled={busy || !secretsFilled}
                        loading={pending === `options:${field.key}`}
                        loadingText="불러오는 중…"
                        onClick={() => void loadOptions(field)}
                      >
                        불러오기
                      </Button>
                    </div>
                  ) : (
                    <Input
                      id={`connector-field-${field.key}`}
                      name={field.key}
                      type={field.secret ? "password" : "text"}
                      autoComplete="off"
                      value={values[field.key] ?? ""}
                      disabled={busy}
                      onChange={(event) => setField(field, event.target.value)}
                    />
                  )}
                  {field.description ? (
                    <p className="text-sm text-muted-foreground">
                      {field.description}
                    </p>
                  ) : null}
                  {field.hasOptions &&
                  field.autoSelectSingle &&
                  options[field.key]?.length === 1 ? (
                    <p className="text-sm text-muted-foreground">
                      하나뿐이라 자동으로 골랐어요.
                    </p>
                  ) : null}
                </div>
              ))}
              <Button
                type="submit"
                disabled={busy || !complete}
                loading={pending === "save"}
                loadingText="연결하는 중…"
              >
                {status === "DISCONNECTED" ? "연결하기" : "값 바꾸기"}
              </Button>
            </form>
          ) : null}
          {available && status === "PENDING" ? (
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
          <section className="space-y-2">
            <h2 className="text-sm font-semibold">할 수 있는 일</h2>
            <ConnectorTools tools={connector?.tools ?? []} />
            {connection.undeclaredTools > 0 ? (
              <Notice
                variant="info"
                role="status"
                data-testid="connection-undeclared"
              >
                이 서비스가 알려 주지 않은 도구 {connection.undeclaredTools}개는
                쓰지 않아요.
              </Notice>
            ) : null}
          </section>
          <ConnectorGrants connectorId={id} refreshKey={connection} />
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
          {error ? (
            <Notice variant="error" role="alert">
              {error}
            </Notice>
          ) : null}
        </CardContent>
      </Card>
    </div>
  );
}
