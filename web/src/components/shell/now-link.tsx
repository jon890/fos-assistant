"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { Inbox } from "lucide-react";
import { nowLinkLabel } from "@/lib/attention";
import { ATTENTION_CHANGED_EVENT, readNowCount } from "@/lib/attention-api";
import { NavPending } from "./nav-pending";

/**
 * 사이드바 최상단의 「지금 볼 것」 링크다. 주요 화면 메뉴가 아니어서 대화 목록이 길어도 스크롤 없이 보인다.
 *
 * <p>배지는 `nowCount` 하나만 그린다. 알림 단추의 읽지 않은 수와 섞이지 않게 muted 바탕에 테두리 없이 그리고,
 * 낭독기에서는 숨긴 채 링크의 `aria-label` 이 수를 읽는다. 화면을 옮길 때마다 다시 읽고,
 * 지금 화면 안의 동작처럼 경로가 그대로인 변화는 `ATTENTION_CHANGED_EVENT` 를 받아 다시 읽는다.
 */
export function NowLink({ onNavigate }: { onNavigate(href: string): void }) {
  const pathname = usePathname();
  const [nowCount, setNowCount] = useState(0);
  useEffect(() => {
    // 읽기마다 차례 번호를 붙여, 늦게 온 앞선 답이 나중 답을 덮지 않게 한다.
    let latest = 0;
    let stale = false;
    const read = () => {
      const turn = ++latest;
      void readNowCount().then((count) => {
        if (!stale && turn === latest) setNowCount(count);
      });
    };
    read();
    window.addEventListener(ATTENTION_CHANGED_EVENT, read);
    return () => {
      stale = true;
      window.removeEventListener(ATTENTION_CHANGED_EVENT, read);
    };
  }, [pathname]);

  const current = pathname === "/now";
  return (
    <Link
      href="/now"
      prefetch={false}
      aria-label={nowLinkLabel(nowCount)}
      aria-current={current ? "page" : undefined}
      onClick={() => onNavigate("/now")}
      className={`mb-4 flex shrink-0 items-center gap-2 rounded-md px-3 py-2 text-sm hover:bg-accent hover:text-foreground ${
        current
          ? "bg-accent font-medium text-foreground"
          : "text-muted-foreground"
      }`}
    >
      <Inbox aria-hidden="true" className="size-4 shrink-0" />
      지금 볼 것
      {nowCount > 0 ? (
        <span
          aria-hidden="true"
          data-testid="now-count"
          className="ml-2 rounded-full bg-muted px-1.5 py-0.5 text-xs text-foreground"
        >
          {nowCount}
        </span>
      ) : null}
      <NavPending />
    </Link>
  );
}
