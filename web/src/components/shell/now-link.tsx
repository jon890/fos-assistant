"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Inbox } from "lucide-react";
import { nowIndicator, nowLinkLabel } from "@/lib/attention";
import { NavPending } from "./nav-pending";
import { NowBadge } from "./now-badge";
import { useNowStatus } from "./now-status";

/**
 * 사이드바의 「지금 볼 것」 링크다. 주요 화면 메뉴가 아니어서 대화 목록이 길어도 스크롤 없이 보인다.
 *
 * <p>할 것이 있으면 그 수를, 할 것은 없고 읽지 않은 알림만 있으면 점을 붙인다. 두 수를 더하지 않는다.
 * 수는 `NowStatusProvider` 가 읽고, 낭독기에서는 표시를 숨긴 채 링크의 `aria-label` 이 읽는다.
 */
export function NowLink({ onNavigate }: { onNavigate(href: string): void }) {
  const pathname = usePathname();
  const { nowCount, unreadCount } = useNowStatus();

  const current = pathname === "/now";
  return (
    <Link
      href="/now"
      prefetch={false}
      aria-label={nowLinkLabel(nowCount, unreadCount > 0)}
      aria-current={current ? "page" : undefined}
      onClick={() => onNavigate("/now")}
      className={`mb-1 flex shrink-0 items-center gap-2 rounded-md px-3 py-2 text-sm hover:bg-accent hover:text-foreground ${
        current
          ? "bg-accent font-medium text-foreground"
          : "text-muted-foreground"
      }`}
    >
      <Inbox aria-hidden="true" className="size-4 shrink-0" />
      지금 볼 것
      <NowBadge indicator={nowIndicator(nowCount, unreadCount)} />
      <NavPending />
    </Link>
  );
}
