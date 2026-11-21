"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { SecretConfirmDialog } from "@/components/admin/secret-confirm-dialog";
import { formatWhen } from "@/lib/format";
import { vapidConfirmText, type VapidSecret } from "@/lib/server-secret-api";

type Confirming = "regenerate" | "delete" | null;

/**
 * 웹 푸시 VAPID 키 한 줄이다. 공개 키는 원문을 보이고 비밀 키는 없다(서버 비밀값 스펙 REQ2, REQ6).
 * 키가 있으면 바꾸거나 지우기 전에 기기가 해제된다는 확인 창을 거친다.
 */
export function VapidSecretRow({
  vapid,
  storeAvailable,
  busy,
  onRegenerate,
  onDelete,
}: {
  vapid: VapidSecret;
  storeAvailable: boolean;
  busy: boolean;
  onRegenerate(subject: string): Promise<void>;
  onDelete(): Promise<void>;
}) {
  const [subject, setSubject] = useState("");
  const [confirming, setConfirming] = useState<Confirming>(null);

  async function run(action: () => Promise<void>) {
    await action();
    setConfirming(null);
  }

  return (
    <section
      data-testid="secret-vapid"
      className="space-y-3 rounded-md border border-border p-3"
    >
      <div className="min-w-0 space-y-1 text-sm">
        <div className="flex flex-wrap items-center gap-2">
          <p className="font-medium">웹 푸시 VAPID 키</p>
          <Badge variant={vapid.set ? "success" : "outline"}>
            {vapid.set ? "입력됨" : "비어 있음"}
          </Badge>
        </div>
        <p className="text-muted-foreground">
          웹 푸시를 보낼 때 서명하는 키예요. 서버가 만들고 비밀 키는 다시 보이지
          않아요. 키가 없으면 푸시만 꺼져 있어요.
        </p>
        {vapid.publicKey ? (
          <p className="break-all text-muted-foreground">
            공개 키 <code>{vapid.publicKey}</code>
          </p>
        ) : null}
        {vapid.subject ? (
          <p className="text-muted-foreground">{`연락처 ${vapid.subject}`}</p>
        ) : null}
        {vapid.updatedAt ? (
          <p className="text-muted-foreground">
            {`마지막 변경 ${formatWhen(vapid.updatedAt)}`}
            {vapid.updatedBy
              ? ` · ${vapid.updatedBy.name ?? "알 수 없는 사용자"}`
              : ""}
          </p>
        ) : null}
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Input
          aria-label="VAPID 연락처"
          placeholder="연락처(비우면 내 이메일)"
          value={subject}
          onChange={(event) => setSubject(event.target.value)}
          className="max-w-xs"
          disabled={busy || !storeAvailable}
        />
        <Button
          size="sm"
          variant="outline"
          disabled={busy || !storeAvailable}
          onClick={() =>
            vapid.set
              ? setConfirming("regenerate")
              : void run(() => onRegenerate(subject))
          }
        >
          새로 만들기
        </Button>
        {vapid.set ? (
          <Button
            size="sm"
            variant="destructive"
            disabled={busy}
            onClick={() => setConfirming("delete")}
          >
            지우기
          </Button>
        ) : null}
      </div>
      {confirming === "regenerate" ? (
        <SecretConfirmDialog
          title="VAPID 키를 새로 만들까요?"
          description={vapidConfirmText(vapid.deviceCount)}
          confirmLabel="새로 만들기"
          busyLabel="만드는 중…"
          destructive
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => void run(() => onRegenerate(subject))}
        />
      ) : null}
      {confirming === "delete" ? (
        <SecretConfirmDialog
          title="VAPID 키를 지울까요?"
          description={`${vapidConfirmText(vapid.deviceCount)} 푸시가 꺼집니다.`}
          confirmLabel="지우기"
          busyLabel="지우는 중…"
          destructive
          busy={busy}
          onCancel={() => setConfirming(null)}
          onConfirm={() => void run(onDelete)}
        />
      ) : null}
    </section>
  );
}
