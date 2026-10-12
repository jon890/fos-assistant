"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import {
  readConversationGrants,
  revokeConversationGrant,
  type ConversationGrant,
} from "@/lib/connector-action";

type Loaded = { conversationId: string; grants: ConversationGrant[] };

/**
 * 이 대화에서 계속 허용 중인 동작을 입력창 위에 한 줄씩 보이고, 「다시 묻기」 로 거두게 한다.
 *
 * <p>허락은 이 대화에서만 통하고 다른 대화에서는 다시 묻는다. 승인 카드에서 허락을 주면 서버가 `approval` 사건을 내므로
 * `refreshKey` 가 바뀌어 다시 읽는다. 허락이 없거나 읽지 못하면 아무것도 그리지 않는다.
 */
export function ConversationGrantList({
  conversationId,
  refreshKey,
}: {
  conversationId: string | null;
  refreshKey: number;
}) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reloads, setReloads] = useState(0);
  const [revoking, setRevoking] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (conversationId === null) return;
    let stale = false;
    void readConversationGrants(conversationId).then((result) => {
      if (stale) return;
      setLoaded((current) =>
        result.ok
          ? { conversationId, grants: result.data }
          : current?.conversationId === conversationId
            ? current
            : { conversationId, grants: [] },
      );
    });
    return () => {
      stale = true;
    };
  }, [conversationId, refreshKey, reloads]);

  async function revoke(grantId: number) {
    if (conversationId === null || revoking !== null) return;
    setRevoking(grantId);
    setError(null);
    const result = await revokeConversationGrant(conversationId, grantId);
    setRevoking(null);
    if (!result.ok)
      setError("다시 묻도록 바꾸지 못했어요. 잠시 뒤 다시 눌러 주세요.");
    setReloads((count) => count + 1);
  }

  // 앞선 대화에서 읽은 허락을 다른 대화에 그리지 않는다.
  const grants =
    loaded !== null && loaded.conversationId === conversationId
      ? loaded.grants
      : [];
  if (grants.length === 0) return null;

  return (
    <div
      data-testid="conversation-grant-list"
      role="region"
      aria-label="이 대화에서 계속 허용 중인 동작"
      className="mx-auto mb-2 flex w-full max-w-3xl flex-col gap-1 px-1 text-sm"
    >
      {grants.map((grant) => (
        <div
          key={grant.grantId}
          data-testid="conversation-grant"
          className="flex min-w-0 flex-wrap items-center gap-2 rounded-md bg-muted px-3 py-1.5"
        >
          <p className="min-w-0 flex-1 break-words text-muted-foreground">
            이 대화에서 묻지 않고 실행해요: 「{grant.title}」
          </p>
          <Button
            size="sm"
            variant="ghost"
            data-testid="conversation-grant-revoke"
            disabled={revoking !== null}
            loading={revoking === grant.grantId}
            onClick={() => void revoke(grant.grantId)}
          >
            다시 묻기
          </Button>
        </div>
      ))}
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
    </div>
  );
}
