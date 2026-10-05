"use client";

import Link from "next/link";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
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
  nowLinkLabel,
  pruneControls,
  visibleNowCount,
  type AttentionCard,
} from "@/lib/attention";
import { AddFollowUpButton } from "./add-follow-up-button";
import { NowItem } from "./now-item";
import type { ItemControl } from "./now-item-controls";
import { ProactiveReportItem } from "./proactive-report-item";

/**
 * 지금 화면의 카드 하나다. 머리의 수는 서버가 상한 전에 센 `nowCount` 이고, 보이는 항목을 다시 세지 않는다.
 * 이 화면에서 숨기거나 미룬 `NOW` 항목만큼은 다시 읽지 않고 빼서 그리고, 되돌리면 다시 더한다.
 * 항목마다 건 제어는 이 카드가 열쇠별 표 하나로 갖고 각 항목에 내려 준다.
 * 「내 차례」 카드 끝에는 「할 일 더하기」 를 둔다.
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
  const [controls, setControls] = useState<ReadonlyMap<string, ItemControl>>(
    () => new Map(),
  );
  // 다시 읽은 응답에서 빠진 항목의 제어는 그 자리에서 버린다. 같은 열쇠로 돌아온 항목에 옛 제어가 남지 않게 한다.
  const [seenItems, setSeenItems] = useState(card.items);
  if (seenItems !== card.items) {
    setSeenItems(card.items);
    setControls(pruneControls(controls, card.items));
  }
  const more = moreText(card);
  const nowCount = visibleNowCount(card, controls);

  function changeControl(itemKey: string, next: ItemControl | null) {
    setControls((previous) => {
      const updated = new Map(previous);
      if (next === null) updated.delete(itemKey);
      else updated.set(itemKey, next);
      return updated;
    });
  }

  return (
    <Card data-testid={`now-card-${card.key}`}>
      <CardHeader>
        <CardTitle>
          <h2>{cardTitle(card.key)}</h2>
        </CardTitle>
        {nowCount > 0 ? (
          <CardAction>
            <Badge
              variant="warning"
              aria-hidden="true"
              data-testid="card-now-count"
            >
              {nowCount}
            </Badge>
            <span className="sr-only">{nowLinkLabel(nowCount)}</span>
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
            {card.items.map((item) =>
              card.key === "reports" ? (
                <ProactiveReportItem key={item.itemKey} item={item} />
              ) : (
                <NowItem
                  key={item.itemKey}
                  card={card.key}
                  item={item}
                  readAt={readAt}
                  control={controls.get(item.itemKey) ?? null}
                  onControlChange={(next) => changeControl(item.itemKey, next)}
                />
              ),
            )}
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
        {card.key === "needs_me" ? <AddFollowUpButton /> : null}
      </CardContent>
    </Card>
  );
}
