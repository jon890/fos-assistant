"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Notice } from "@/components/ui/notice";
import {
  OAUTH_REASON_PARAM,
  OAUTH_RESULT_PARAM,
  completeOAuth,
  oauthOutcomeMessage,
} from "@/lib/connection-oauth";

type Query = Parameters<typeof completeOAuth>[0];

/**
 * 돌아온 검색어로 연결을 마친다. 결과는 그 플러그인의 연결 화면이 까닭과 「다시 시도」 로 보인다(스펙 DECISION20).
 * 상태 값을 믿을 수 없어 어느 플러그인인지 모르면 이 자리에서 까닭을 보인다.
 */
export function OAuthCallback({ query }: { query: Query }) {
  const router = useRouter();
  const sent = useRef(false);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    // 코드는 한 번만 쓸 수 있다. 개발 모드의 두 번 실행에도 한 번만 보낸다.
    if (sent.current) return;
    sent.current = true;
    void completeOAuth(query).then((result) => {
      if (!result.ok) return setMessage(result.message);
      const { outcome, connectorId, authorizeUrl, reason } = result.data;
      if (outcome === "REAUTHORIZE" && authorizeUrl) {
        window.location.assign(authorizeUrl);
        return;
      }
      if (!connectorId) {
        return setMessage(
          oauthOutcomeMessage(outcome, reason) ?? "연결을 마치지 못했어요.",
        );
      }
      const params = new URLSearchParams({ [OAUTH_RESULT_PARAM]: outcome });
      if (reason) params.set(OAUTH_REASON_PARAM, reason);
      router.replace(`/connections/${connectorId}?${params.toString()}`);
    });
  }, [query, router]);

  return (
    <div
      className="mx-auto w-full max-w-2xl space-y-3"
      data-testid="oauth-callback"
    >
      {message === null ? (
        <p className="text-sm text-muted-foreground">연결을 마치는 중…</p>
      ) : (
        <>
          <Notice variant="error" role="alert">
            {message}
          </Notice>
          <Link
            href="/connections"
            className="text-sm text-foreground underline underline-offset-4"
          >
            외부 서비스 연결로 돌아가기
          </Link>
        </>
      )}
    </div>
  );
}
