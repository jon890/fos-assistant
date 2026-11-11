"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { HANDOFF_TEXT, handoffBrowserHref, type Handoff } from "@/lib/handoff";
import { readHandoffs } from "@/lib/handoff-api";

type Loaded = { conversationId: string; handoffs: Handoff[] };

/**
 * 입력창 위에 그 대화의 화면 넘기기 카드를 보인다. 플러그인이 사람이 해야 하는 화면에서 멈추면 생긴다.
 *
 * <p>「내 브라우저 열기」 는 남긴 탭을 고른 채 「내 브라우저」 를 새 창으로 연다. 「다 했어요」 는 정해 둔 글을 다음 메시지로
 * 보낸다. 새 turn 이 시작되면 서버가 카드를 닫는다. 입력창에 직접 답해도 같다.
 *
 * <p>대화나 `refreshKey` 가 바뀌면 다시 읽는다. 읽지 못해도 대화를 막지 않게 조용히 비운다.
 */
export function HandoffList({
  conversationId,
  refreshKey,
  onDone,
}: {
  conversationId: string | null;
  refreshKey: number;
  onDone: (text: string) => void;
}) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  /** 「다 했어요」 를 누른 카드다. 서버가 닫기 전에 다시 누르지 않게 감춘다 */
  const [done, setDone] = useState<string[]>([]);

  useEffect(() => {
    if (conversationId === null) return;
    let stale = false;
    void readHandoffs(conversationId).then((result) => {
      if (stale) return;
      setLoaded((current) =>
        result.ok
          ? { conversationId, handoffs: result.data }
          : current?.conversationId === conversationId
            ? current
            : { conversationId, handoffs: [] },
      );
    });
    return () => {
      stale = true;
    };
  }, [conversationId, refreshKey]);

  if (loaded === null || loaded.conversationId !== conversationId) return null;
  const shown = loaded.handoffs.filter(
    (handoff) => !done.includes(handoff.handoffId),
  );
  if (shown.length === 0) return null;
  return (
    <div className="px-1">
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-2 pb-2">
        {shown.map((handoff) => (
          <HandoffCard
            key={handoff.handoffId}
            handoff={handoff}
            onDone={() => {
              setDone((current) => [...current, handoff.handoffId]);
              onDone(HANDOFF_TEXT[handoff.need].done);
            }}
          />
        ))}
      </div>
    </div>
  );
}

function HandoffCard({
  handoff,
  onDone,
}: {
  handoff: Handoff;
  onDone: () => void;
}) {
  const text = HANDOFF_TEXT[handoff.need];
  return (
    <section
      aria-label="화면 넘기기"
      data-testid="handoff-card"
      className="rounded-lg border border-border bg-card p-3 text-sm"
    >
      <p className="font-medium">{text.title}</p>
      <p className="mt-1 text-muted-foreground">
        「{handoff.connectorTitle}」에서 사람이 해야 하는 화면을 만나 멈췄어요.
        그 탭을 「내 브라우저」에 열어 두었어요. 마친 뒤 「다 했어요」를 눌러
        주세요.
      </p>
      <div className="mt-2 flex flex-wrap gap-2">
        <Button asChild size="sm" variant="outline">
          <a href={handoffBrowserHref(handoff)} target="_blank" rel="noopener">
            내 브라우저 열기
          </a>
        </Button>
        <Button size="sm" onClick={onDone}>
          다 했어요
        </Button>
      </div>
    </section>
  );
}
