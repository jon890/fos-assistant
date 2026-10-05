"use client";

import { Ellipsis } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  snoozeUntil,
  type AttentionCardKey,
  type AttentionItem,
} from "@/lib/attention";
import { hideItem, restoreItem, snoozeItem } from "@/lib/attention-api";

/** 항목에 건 제어다. 숨기기는 상태가 바뀔 때까지, 미루기는 정한 시각까지다. */
export type ItemControl = "hidden" | "snoozed";

/**
 * 항목 오른쪽의 제어 메뉴다. 숨기기와 미루기는 원래 기록을 바꾸지 않고, 제어는 이 카드에만 걸린다.
 *
 * <p>성공하면 화면을 다시 읽지 않고 `onControlled` 로 그 줄만 바꿔 그리게 한다. 바로 되돌릴 수 있게 하려는 것이다.
 */
export function NowItemControls({
  card,
  item,
  onControlled,
  onError,
}: {
  card: AttentionCardKey;
  item: AttentionItem;
  onControlled(control: ItemControl): void;
  onError(message: string | null): void;
}) {
  const [busy, setBusy] = useState(false);

  async function apply(control: ItemControl, kind?: "tomorrow" | "week") {
    setBusy(true);
    onError(null);
    const result =
      control === "hidden"
        ? await hideItem(card, item.itemKey, item.stateKey)
        : await snoozeItem(
            card,
            item.itemKey,
            snoozeUntil(kind ?? "tomorrow", new Date()),
          );
    setBusy(false);
    if (result.ok) onControlled(control);
    else onError(result.message);
  }

  return (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label="이 항목 제어"
          disabled={busy}
          className="shrink-0"
        >
          <Ellipsis aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-auto">
        <DropdownMenuItem onSelect={() => void apply("hidden")}>
          숨기기
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={() => void apply("snoozed", "tomorrow")}>
          내일 아침으로 미루기
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={() => void apply("snoozed", "week")}>
          일주일 뒤로 미루기
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * 숨기거나 미룬 뒤 그 줄 대신 그리는 한 줄이다. 「되돌리기」 는 이 카드의 제어를 지우고 원래 줄로 돌린다.
 */
export function ControlledRow({
  card,
  item,
  control,
  onRestored,
  onError,
}: {
  card: AttentionCardKey;
  item: AttentionItem;
  control: ItemControl;
  onRestored(): void;
  onError(message: string | null): void;
}) {
  const [busy, setBusy] = useState(false);

  async function restore() {
    setBusy(true);
    onError(null);
    const result = await restoreItem(card, item.itemKey);
    setBusy(false);
    if (result.ok) onRestored();
    else onError(result.message);
  }

  return (
    <p className="flex flex-wrap items-center gap-x-1 text-sm text-muted-foreground">
      <span>{control === "hidden" ? "숨겼어요" : "미뤘어요"}</span>
      <span aria-hidden="true">·</span>
      <Button
        variant="link"
        size="xs"
        loading={busy}
        onClick={() => void restore()}
        className="px-0"
      >
        되돌리기
      </Button>
    </p>
  );
}
