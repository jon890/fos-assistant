"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { CopyButton } from "@/components/ui/copy-button";
import { Notice } from "@/components/ui/notice";
import { listServiceTokens, type ServiceToken } from "@/lib/service-token-api";
import type { MemoryCollectionOption } from "@/lib/memory-document";
import { ServiceTokenForm } from "./service-token-form";
import { ServiceTokenItem } from "./service-token-item";

export function ServiceTokenPanel({
  initialTokens,
  collections,
}: {
  initialTokens: ServiceToken[];
  collections: MemoryCollectionOption[];
}) {
  const [tokens, setTokens] = useState(initialTokens);
  /**
   * 방금 발급한 토큰의 원문이다. 이 상태에만 두므로 새로고침하면 사라진다.
   * 브라우저 저장소와 주소와 쿠키에 두지 않는다.
   */
  const [issued, setIssued] = useState<string | null>(null);
  const names = new Map(
    collections.map((option) => [option.key, option.displayName]),
  );

  async function reload() {
    const result = await listServiceTokens();
    if (result.ok) setTokens(result.data);
  }

  return (
    <section className="mb-8" aria-labelledby="service-tokens-heading">
      <h2 id="service-tokens-heading" className="mb-1 text-lg font-semibold">
        외부 서비스 연결
      </h2>
      <p className="mb-3 max-w-2xl text-sm leading-6 text-muted-foreground">
        다른 프로그램이 내 문서를 읽을 때 쓰는 토큰이에요. 문서를 읽기만 하고
        고치지 못해요.
      </p>
      <ServiceTokenForm
        collections={collections}
        onIssued={async (token) => {
          setIssued(token);
          await reload();
        }}
      />
      {issued !== null ? (
        <Notice variant="warning" className="mb-4" role="status">
          <p>토큰은 지금 한 번만 보여요. 안전한 곳에 옮겨 적어 주세요.</p>
          <code
            data-testid="issued-service-token"
            className="mt-2 block break-all rounded-md bg-muted px-2 py-1 text-foreground"
          >
            {issued}
          </code>
          <div className="mt-2 flex gap-2">
            <CopyButton text={issued} label="토큰 복사" />
            <Button size="xs" variant="outline" onClick={() => setIssued(null)}>
              확인했어요
            </Button>
          </div>
        </Notice>
      ) : null}
      <div className="grid gap-3">
        {tokens.length > 0 ? (
          tokens.map((token) => (
            <ServiceTokenItem
              key={token.id}
              token={token}
              names={names}
              onChanged={reload}
            />
          ))
        ) : (
          <p className="text-sm text-muted-foreground">
            아직 만든 토큰이 없어요.
          </p>
        )}
      </div>
    </section>
  );
}
