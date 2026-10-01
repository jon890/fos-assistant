"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import {
  CONNECTOR_ID_PATTERN,
  checkConnection,
  connectionStatusLabel,
  disconnectConnection,
  readConnection,
  readConnectors,
  readOptions,
  registerConnection,
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

function NotFound() {
  return (
    <div className="mx-auto w-full max-w-2xl" data-testid="connector-not-found">
      <h1 className="mb-4 text-xl font-semibold">찾을 수 없는 서비스예요</h1>
      <Link
        href="/connections"
        className="text-sm text-primary underline-offset-4 hover:underline"
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
        <p
          role="alert"
          className="rounded-md border border-border bg-muted p-3 text-sm"
        >
          연결 상태를 읽지 못했어요. {loaded.message}
        </p>
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
    setPending(null);
    if (!result.ok) return setError(result.message);
    setLoaded({ kind: "ready", connector, connection: result.data });
  }

  const status = connection.status;
  const shown = fields.flatMap((field) => {
    const value = field.secret
      ? connection.secretPrefixes[field.key]
      : connection.values[field.key];
    return value ? [{ field, value }] : [];
  });

  return (
    <div className="mx-auto w-full max-w-2xl">
      <Card>
        <CardHeader>
          <CardTitle>{title}</CardTitle>
          {connector?.description ? (
            <CardDescription>{connector.description}</CardDescription>
          ) : null}
        </CardHeader>
        <CardContent className="space-y-5">
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-muted px-3 py-2 text-sm">
            <span>
              상태{" "}
              <Badge variant="outline" data-testid="connection-status">
                {connectionStatusLabel(
                  status,
                  connection.restartRequired && status !== "DISCONNECTED",
                )}
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
          {connection.restartRequired ? (
            <p
              role="status"
              className="rounded-md border border-border bg-muted p-3 text-sm"
            >
              관리자가 실행 반영을 확인할 때까지 기다려 주세요.
            </p>
          ) : null}
          {!available ? (
            <p
              role="status"
              className="rounded-md border border-border bg-muted p-3 text-sm"
            >
              지금은 쓸 수 없어요. 연결을 해제할 수만 있어요.
            </p>
          ) : null}
          {status === "READY" && connection.agentCode ? (
            <p className="text-sm">
              <Link
                href={`/agents/${connection.agentCode}`}
                className="text-primary underline-offset-4 hover:underline"
              >
                에이전트 열기
              </Link>
            </p>
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
            <p
              role="alert"
              className="rounded-md border border-border bg-muted p-3 text-sm"
            >
              {error}
            </p>
          ) : null}
        </CardContent>
      </Card>
    </div>
  );
}
