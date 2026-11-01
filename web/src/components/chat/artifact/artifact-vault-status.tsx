"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { describeFailure } from "@/components/error-message";
import {
  retryAutoVault,
  VAULT_ERRORS,
  type AutoVaultState,
  type AutoVaultStatus,
} from "@/lib/artifact-vault-api";

type Props = {
  conversationId: string;
  messageId: number;
  path: string;
  state: AutoVaultState | null;
  error: string | null;
};

/** 실패 까닭의 오류 코드를 문구로 바꾼다. 모르는 코드는 다시 시도하라는 문구다. */
function failureText(code: string | null): string {
  return (
    (code ? VAULT_ERRORS[code] : undefined) ??
    "보관하지 못했어요. 다시 시도해 주세요."
  );
}

/**
 * 답 아래 결과물 줄 옆에 자동 보관 상태를 보인다(#530 PR 3).
 *
 * <p>자동 보관 대상이 아니거나 지운 보관본이라 다시 만들지 않았으면 아무것도 그리지 않는다. 실패면 까닭과 「다시 보관」 을
 * 보인다. 보관이 실패해도 답과 원본 파일은 그대로라 결과물은 계속 열 수 있다.
 */
export function ArtifactVaultStatus({
  conversationId,
  messageId,
  path,
  state,
  error,
}: Props) {
  // 다시 보관한 응답만 지역 상태로 둔다. 그 밖에는 이력을 다시 읽어 온 값을 그대로 보인다.
  const [retried, setRetried] = useState<AutoVaultStatus | null>(null);
  const status = retried ?? { state, error };
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function retry() {
    setBusy(true);
    setMessage(null);
    try {
      const response = await retryAutoVault(conversationId, messageId, path);
      if (!response.ok) {
        setMessage(await describeFailure(response, VAULT_ERRORS));
        return;
      }
      setRetried((await response.json()) as AutoVaultStatus);
    } catch {
      setMessage("보관하지 못했어요. 다시 시도해 주세요.");
    } finally {
      setBusy(false);
    }
  }

  if (status.state === "PENDING" || status.state === "RUNNING")
    return (
      <span
        data-testid="artifact-auto-vault"
        className="shrink-0 text-xs text-muted-foreground"
      >
        보관 중
      </span>
    );
  if (status.state === "DONE")
    return (
      <span
        data-testid="artifact-auto-vault"
        className="shrink-0 text-xs text-muted-foreground"
      >
        보관 완료
      </span>
    );
  if (status.state !== "FAILED") return null;
  return (
    <span
      data-testid="artifact-auto-vault"
      className="flex min-w-0 flex-wrap items-center gap-2 text-xs"
    >
      <span role="alert" className="text-destructive">
        보관 실패: {message ?? failureText(status.error)}
      </span>
      <Button
        variant="outline"
        size="sm"
        disabled={busy}
        data-testid="artifact-auto-vault-retry"
        onClick={() => void retry()}
      >
        다시 보관
      </Button>
    </span>
  );
}
