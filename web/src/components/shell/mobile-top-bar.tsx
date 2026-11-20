"use client";

import Link from "next/link";
import { Menu, SquarePen } from "lucide-react";
import { cn } from "cn";
import { NotificationBell } from "@/components/notification/notification-bell";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { NEW_CONVERSATION_PATH } from "@/lib/room";

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
      <NotificationBell size="icon" />
      <TooltipButton label="새 대화" size="icon" asChild>
        <Link href={NEW_CONVERSATION_PATH} onClick={onStartNew}>
          <SquarePen aria-hidden="true" className="size-5" />
        </Link>
      </TooltipButton>
    </header>
  );
}
