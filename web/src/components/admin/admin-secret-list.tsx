"use client";

import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { describeAdminError } from "@/components/error-message";
import { SecretConfirmDialog } from "@/components/admin/secret-confirm-dialog";
import { formatWhen } from "@/lib/format";
import {
  deleteGatewaySecret,
  fetchServerSecrets,
  regenerateConfirmText,
  regenerateGatewaySecret,
  reinstallSummary,
  type BrowserGatewaySecret,
  type GatewayRegenerated,
  type ServerSecretOverview,
} from "@/lib/server-secret-api";

/** 상태를 읽는다. 읽지 못하면 `null` 이다. */
async function readOverview(): Promise<ServerSecretOverview | null> {
  try {
    const response = await fetchServerSecrets();
    return response.ok
      ? ((await response.json()) as ServerSecretOverview)
      : null;
  } catch {
    return null;
  }
}

/** 실패한 응답을 관리 화면 문구로 바꾼다. */
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

/** 브라우저 중계 서명 비밀 한 줄이다. 원문은 없고 입력됨 여부와 앞부분, 마지막 변경만 보인다. */
function GatewaySecretRow({
  gateway,
  storeAvailable,
  busy,
  creating,
  onRegenerate,
  onDelete,
}: {
  gateway: BrowserGatewaySecret;
  storeAvailable: boolean;
  busy: boolean;
  creating: boolean;
  onRegenerate(): void;
  onDelete(): void;
}) {
  return (
    <section
      data-testid="secret-browser-gateway"
      className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-border p-3"
    >
      <div className="min-w-0 space-y-1 text-sm">
        <div className="flex flex-wrap items-center gap-2">
          <p className="font-medium">브라우저 중계 서명 비밀</p>
          <Badge variant={gateway.set ? "success" : "outline"}>
            {gateway.set ? "입력됨" : "비어 있음"}
          </Badge>
          {gateway.prefix ? (
            <code className="text-muted-foreground">{gateway.prefix}…</code>
          ) : null}
        </div>
        <p className="text-muted-foreground">
          브라우저를 쓰는 플러그인이 사용자 브라우저에 닿을 때 쓰는 표식을
          서명해요. 값은 서버가 만들고 다시 보이지 않아요.
        </p>
        {gateway.updatedAt ? (
          <p className="text-muted-foreground">
            {`마지막 변경 ${formatWhen(gateway.updatedAt)}`}
            {gateway.updatedBy
              ? ` · ${gateway.updatedBy.name ?? "알 수 없는 사용자"}`
              : " · 기존 설정에서 가져옴"}
          </p>
        ) : null}
        {gateway.gatewayUrlConfigured ? null : (
          <p className="text-muted-foreground">
            중계 주소가 설정되지 않아 값이 있어도 중계는 꺼져 있어요.
          </p>
        )}
      </div>
      <div className="flex gap-2">
        <Button
          size="sm"
          variant="outline"
          disabled={busy || !storeAvailable}
          loading={creating}
          loadingText="만드는 중…"
          onClick={onRegenerate}
        >
          새로 만들기
        </Button>
        {gateway.set ? (
          <Button
            size="sm"
            variant="destructive"
            disabled={busy}
            onClick={onDelete}
          >
            지우기
          </Button>
        ) : null}
      </div>
    </section>
  );
}

type Confirming = "regenerate" | "delete" | null;

/**
 * 관리자 「서버 비밀값」 화면이다. 원문은 보이지 않고 입력됨 여부와 앞부분만 보인다(서버 비밀값 스펙 REQ2, REQ3).
 * 브라우저 중계 서명 비밀은 서버가 만든다. 이미 값이 있으면 다시 만들기 전에 확인 창을 띄운다(REQ18).
 */
export function AdminSecretList() {
  const [overview, setOverview] = useState<ServerSecretOverview | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState<Confirming>(null);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<string[] | null>(null);

  function apply(next: ServerSecretOverview | null) {
    if (next === null) setLoadError(true);
    else {
      setOverview(next);
      setLoadError(false);
    }
  }

  useEffect(() => {
    void readOverview().then(apply);
  }, []);

  async function regenerate() {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const response = await regenerateGatewaySecret();
      if (response.ok) {
        const body = (await response.json()) as GatewayRegenerated;
        setResult(reinstallSummary(body.reinstall));
      } else setError(await failureOf(response));
    } catch {
      setError("요청을 보내지 못했어요. 잠시 뒤 다시 해 주세요.");
    }
    apply(await readOverview());
    setBusy(false);
    setConfirming(null);
  }

  async function remove() {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const response = await deleteGatewaySecret();
      if (!response.ok) setError(await failureOf(response));
    } catch {
      setError("요청을 보내지 못했어요. 잠시 뒤 다시 해 주세요.");
    }
    apply(await readOverview());
    setBusy(false);
    setConfirming(null);
  }

  const gateway = overview?.browserGateway;

  return (
    <div className="mx-auto w-full max-w-3xl">
      <h1 className="mb-4 text-xl font-semibold">서버 비밀값</h1>
      {loadError && overview === null ? (
        <Notice variant="error" role="alert">
          서버 비밀값을 읽지 못했어요. 화면을 다시 열어 주세요.
        </Notice>
      ) : overview === null || !gateway ? (
        <p className="text-sm text-muted-foreground">불러오는 중…</p>
      ) : (
        <div className="space-y-3">
          {overview.storeAvailable ? null : (
            <Notice variant="error" role="alert">
              KEK 가 없어 저장할 수 없습니다
            </Notice>
          )}
          <GatewaySecretRow
            gateway={gateway}
            storeAvailable={overview.storeAvailable}
            busy={busy}
            creating={busy && confirming === null}
            onRegenerate={() =>
              gateway.set ? setConfirming("regenerate") : void regenerate()
            }
            onDelete={() => setConfirming("delete")}
          />
        </div>
      )}
      {result ? (
        <Notice variant="info" role="status" className="mt-3">
          {result.map((line) => (
            <p key={line}>{line}</p>
          ))}
        </Notice>
      ) : null}
      {error ? (
        <Notice variant="error" role="alert" className="mt-3">
          {error}
        </Notice>
      ) : null}
      {confirming === "regenerate" && gateway ? (
        <SecretConfirmDialog
          title="중계 서명 비밀을 새로 만들까요?"
          description={regenerateConfirmText(gateway.bindingCount)}
          confirmLabel="새로 만들기"
          busyLabel="만드는 중…"
          destructive={false}
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => void regenerate()}
        />
      ) : null}
      {confirming === "delete" ? (
        <SecretConfirmDialog
          title="중계 서명 비밀을 지울까요?"
          description="지우면 브라우저 중계가 꺼져 브라우저를 쓰는 플러그인이 동작하지 않아요."
          confirmLabel="지우기"
          busyLabel="지우는 중…"
          destructive
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => void remove()}
        />
      ) : null}
    </div>
  );
}
