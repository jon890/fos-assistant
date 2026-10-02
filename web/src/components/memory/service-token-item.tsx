"use client";

import { useState } from "react";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatWhen } from "@/lib/format";
import { serviceTokenStatus } from "@/lib/service-token";
import { revokeServiceToken, type ServiceToken } from "@/lib/service-token-api";

export function ServiceTokenItem({
  token,
  names,
  onChanged,
}: {
  token: ServiceToken;
  names: Map<string, string>;
  onChanged(): Promise<void>;
}) {
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const status = serviceTokenStatus(token, new Date());

  async function revoke() {
    setBusy(true);
    setError(undefined);
    const result = await revokeServiceToken(token.id);
    setBusy(false);
    setConfirming(false);
    if (!result.ok) {
      setError(result.message);
      return;
    }
    await onChanged();
  }

  return (
    <article className="rounded-md border border-border p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="min-w-0 break-words font-semibold">{token.label}</h3>
        {status === "expiring" ? (
          <Badge variant="warning">곧 만료돼요</Badge>
        ) : null}
        {status === "expired" ? (
          <Badge variant="destructive">만료됐어요</Badge>
        ) : null}
        {status === "revoked" ? (
          <Badge variant="outline">폐기했어요</Badge>
        ) : null}
      </div>
      <p className="mt-1 text-sm">
        {token.collections.map((grant, index) => (
          <span key={grant.collection}>
            {index > 0 ? ", " : ""}
            {names.get(grant.collection) ?? grant.collection}
            {grant.allowSensitive ? (
              <Badge variant="warning" className="ml-1">
                민감 포함
              </Badge>
            ) : null}
          </span>
        ))}
      </p>
      <p className="mt-1 text-xs text-muted-foreground">
        {token.lastUsedAt
          ? `마지막 사용 ${formatWhen(token.lastUsedAt)}`
          : "아직 쓰지 않았어요"}{" "}
        · {formatWhen(token.expiresAt)}에 만료
      </p>
      {error ? (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {error}
        </p>
      ) : null}
      {status === "active" || status === "expiring" ? (
        <Button
          size="sm"
          variant="destructive"
          className="mt-3"
          onClick={() => setConfirming(true)}
        >
          폐기
        </Button>
      ) : null}
      {confirming ? (
        <AlertDialog
          open
          onOpenChange={(open) => {
            if (!open && !busy) setConfirming(false);
          }}
        >
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>토큰을 폐기할까요?</AlertDialogTitle>
              <AlertDialogDescription>
                이 토큰을 쓰는 프로그램은 더 이상 문서를 읽지 못해요.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel asChild>
                <Button variant="outline" disabled={busy}>
                  취소
                </Button>
              </AlertDialogCancel>
              <Button
                variant="destructive"
                loading={busy}
                loadingText="폐기 중"
                onClick={() => void revoke()}
              >
                폐기하기
              </Button>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      ) : null}
    </article>
  );
}
