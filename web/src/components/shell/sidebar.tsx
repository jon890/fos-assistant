"use client";

import Link from "next/link";
import { useEffect, useState, type RefObject } from "react";
import { PanelLeftClose } from "lucide-react";
import { Button } from "@/components/ui/button";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { ConversationNav } from "./conversation-nav";
import { MainNav } from "./main-nav";
import { NAV_PENDING_EVENT } from "./nav-pending";
import { useConversations } from "./conversations-provider";

export function Sidebar({ isAdmin, displayName, onNavigate, searchRef, onCollapse, showStatus = true }: {
  isAdmin: boolean;
  displayName?: string;
  /** 링크를 누를 때 그 목적지를 받는다. 서랍은 목적지가 지금 경로와 같을 때만 곧바로 닫는다 */
  onNavigate(href: string): void;
  searchRef: RefObject<HTMLInputElement | null>;
  onCollapse(): void;
  /** 옮기는 중 안내 영역을 그린다. 붙박이 사이드바와 서랍 가운데 한쪽만 그린다 */
  showStatus?: boolean;
}) {
  const { startNew } = useConversations();
  const [query, setQuery] = useState("");
  // 옮기는 중인 링크의 개수다. 링크마다 하나씩 NavPending 이 두고, 이동이 끝나거나 그 링크가
  // 사라지면 줄어든다. 0보다 크면 사이드바 전체에서 무언가 옮기는 중이다.
  // 켜짐과 꺼짐은 짝을 이뤄 오지만, 사이드바가 켜짐을 받기 전에 올라온 링크가 꺼짐만 보내는 경우에도
  // 안내가 음수로 남지 않게 0 에서 멈춘다.
  const [pendingCount, setPendingCount] = useState(0);
  useEffect(() => {
    const update = (event: Event) => {
      const pending = (event as CustomEvent<boolean>).detail;
      setPendingCount((count) => Math.max(0, count + (pending ? 1 : -1)));
    };
    window.addEventListener(NAV_PENDING_EVENT, update);
    return () => window.removeEventListener(NAV_PENDING_EVENT, update);
  }, []);

  return (
    <div className="flex h-full min-h-0 flex-col bg-muted px-3 py-4">
      {showStatus ? (
        <span role="status" aria-live="polite" className="sr-only">
          {pendingCount > 0 ? "옮기는 중" : ""}
        </span>
      ) : null}
      <div className="mb-5 flex items-center justify-between gap-2 px-2">
        <Link href="/" aria-label="우리집 비서 홈" onClick={() => { startNew(); onNavigate("/"); }}
          className="truncate text-base font-semibold">우리집 비서</Link>
        <TooltipButton label="사이드바 접기" onClick={onCollapse} className="hidden hover:bg-accent md:inline-flex">
          <PanelLeftClose aria-hidden="true" />
        </TooltipButton>
      </div>
      <Button asChild variant="outline" className="mb-4 justify-start bg-background hover:bg-accent">
        <Link href="/" data-testid="new-conversation-link" onClick={() => { startNew(); onNavigate("/"); }}>새 대화</Link>
      </Button>
      <input ref={searchRef} type="search" aria-label="대화 검색" value={query}
        onChange={(event) => setQuery(event.target.value)} placeholder="대화 검색"
        className="mb-4 w-full rounded-md border border-border bg-background px-3 py-2 text-sm" />
      <ConversationNav onNavigate={onNavigate} query={query} />
      <div className="border-t border-border pt-3">
        <MainNav isAdmin={isAdmin} onNavigate={onNavigate} />
        <div className="mt-3 flex items-center justify-between gap-2 px-3 py-2">
          {displayName ? <span className="min-w-0 truncate text-xs text-muted-foreground" title={displayName}>{displayName}</span> : null}
          <ThemeToggle />
        </div>
      </div>
    </div>
  );
}
