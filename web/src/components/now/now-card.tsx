"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Notice } from "@/components/ui/notice";
import {
  cardEmptyText,
  cardTitle,
  moreText,
  type AttentionCard,
} from "@/lib/attention";
import { FollowUpDialog } from "./follow-up-dialog";
import { NowItem } from "./now-item";

/**
 * 지금 화면의 카드 하나다. 머리의 수는 서버가 상한 전에 센 `nowCount` 이고, 보이는 항목을 다시 세지 않는다.
 * 「내 차례」 카드 끝에는 「할 일 더하기」 를 두고, 저장하면 서버 부품을 다시 읽는다.
 *
 * @param readAt 응답을 읽은 시각. 항목의 상대 시각을 이 시각 기준으로 센다
 */
export function NowCard({
  card,
  readAt,
}: {
  card: AttentionCard;
  readAt: string;
}) {
  const router = useRouter();
  const [adding, setAdding] = useState(false);
  const more = moreText(card);
  return (
    <Card data-testid={`now-card-${card.key}`}>
      <CardHeader>
        <CardTitle>
          <h2>{cardTitle(card.key)}</h2>
        </CardTitle>
        {card.nowCount > 0 ? (
          <CardAction>
            <Badge variant="warning">{card.nowCount}</Badge>
          </CardAction>
        ) : null}
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {card.status === "UNAVAILABLE" ? (
          <Notice variant="warning">
            이 카드를 불러오지 못했어요. 잠시 뒤에 다시 열어 주세요
          </Notice>
        ) : card.items.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            {cardEmptyText(card.key)}
          </p>
        ) : (
          <ul className="flex flex-col gap-3">
            {card.items.map((item) => (
              <NowItem
                key={item.itemKey}
                card={card.key}
                item={item}
                readAt={readAt}
              />
            ))}
          </ul>
        )}
        {more ? (
          more.href ? (
            <Link
              href={more.href}
              prefetch={false}
              className="text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
            >
              {more.text}
            </Link>
          ) : (
            <p className="text-sm text-muted-foreground">{more.text}</p>
          )
        ) : null}
        {card.key === "needs_me" ? (
          <>
            <Button
              variant="outline"
              size="sm"
              className="self-start"
              onClick={() => setAdding(true)}
            >
              할 일 더하기
            </Button>
            <FollowUpDialog
              open={adding}
              onOpenChange={setAdding}
              followUp={null}
              onSaved={() => {
                setAdding(false);
                router.refresh();
              }}
            />
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}
