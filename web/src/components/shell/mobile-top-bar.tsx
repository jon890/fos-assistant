"use client";

import Link from "next/link";
import { Inbox, Menu, SquarePen } from "lucide-react";
import { cn } from "cn";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { nowIndicator, nowLinkLabel } from "@/lib/attention";
import { NEW_CONVERSATION_PATH } from "@/lib/room";
import { NowBadge } from "./now-badge";
import { useNowStatus } from "./now-status";

type Props = {
  /** 가운데에 보일 글자다. 화면이 정한 제목이 없으면 앱 이름이다 */
  title: string;
  appName: string;
  onOpenDrawer(): void;
  onStartNew(): void;
};

/** 좁은 화면 위 막대다. 넓은 화면에서는 CSS 가 숨긴다 */
export function MobileTopBar({
  title,
  appName,
  onOpenDrawer,
  onStartNew,
}: Props) {
  const { nowCount, unreadCount } = useNowStatus();
  return (
    <header
      className={cn(
        "flex flex-nowrap items-center gap-3 md:hidden",
        "h-14 shrink-0 px-4",
        "border-b border-border",
      )}
    >
      <TooltipButton label="사이드바 열기" size="icon" onClick={onOpenDrawer}>
        <Menu aria-hidden="true" className="size-5" />
      </TooltipButton>
      <Link
        href="/"
        aria-label={`${appName} 홈`}
        className="flex h-full min-w-0 flex-1 items-center justify-center rounded-md px-2 text-sm font-medium hover:bg-muted active:bg-accent"
      >
        <span className="min-w-0 truncate">{title}</span>
      </Link>
      {/* 알림 단추가 있던 자리다. 사이드바의 「지금 볼 것」 줄과 같은 규칙으로 수나 점이 붙는다. */}
      <TooltipButton
        label={nowLinkLabel(nowCount, unreadCount > 0)}
        size="icon"
        asChild
        className="relative"
      >
        <Link href="/now" prefetch={false}>
          <Inbox aria-hidden="true" className="size-5" />
          <NowBadge indicator={nowIndicator(nowCount, unreadCount)} floating />
        </Link>
      </TooltipButton>
      <TooltipButton label="새 대화" size="icon" asChild>
        <Link href={NEW_CONVERSATION_PATH} onClick={onStartNew}>
          <SquarePen aria-hidden="true" className="size-5" />
        </Link>
      </TooltipButton>
    </header>
  );
}
