"use client";

import Link from "next/link";
import { Bell } from "lucide-react";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { unreadBadge } from "@/lib/notification";
import { useUnreadNotifications } from "./notifications-provider";

/** `/notifications` 로 가는 링크다. 읽지 않은 알림이 있으면 수를 배지로 보인다. */
export function NotificationBell({
  onNavigate,
  size = "icon-sm",
}: {
  /** 링크를 누를 때 그 목적지를 받는다. 사이드바의 `onNavigate` 와 같다 */
  onNavigate?: (href: string) => void;
  size?: "icon" | "icon-sm";
}) {
  const { unreadCount } = useUnreadNotifications();
  const badge = unreadBadge(unreadCount);
  const label = badge ? `알림, 읽지 않은 알림 ${unreadCount}개` : "알림";
  return (
    <TooltipButton label={label} size={size} asChild className="relative">
      <Link
        href="/notifications"
        onClick={() => onNavigate?.("/notifications")}
      >
        <Bell aria-hidden="true" />
        {badge ? (
          <span
            data-testid="notification-count"
            aria-hidden="true"
            className="absolute -top-1 -right-1 min-w-4 rounded-full bg-foreground px-1 text-center text-xs leading-4 font-semibold text-background"
          >
            {badge}
          </span>
        ) : null}
      </Link>
    </TooltipButton>
  );
}
