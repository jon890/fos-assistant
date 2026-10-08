"use client";

import Link from "next/link";
import { useEffect, useState, type RefObject } from "react";
import { PanelLeftClose, ShieldCheck, SquarePen } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { NotificationBell } from "@/components/notification/notification-bell";
import { ConversationNav } from "./conversation-nav";
import { MainNav } from "./main-nav";
import { NavPending, NAV_PENDING_EVENT } from "./nav-pending";
import { NowLink } from "./now-link";
import { useAppName, useShellAccount } from "./shell-account";
import { useConversations } from "./conversations-provider";

export function Sidebar({
  onNavigate,
  searchRef,
  onCollapse,
  showStatus = true,
}: {
  /** 링크를 누를 때 그 목적지를 받는다. 서랍은 목적지가 지금 경로와 같을 때만 곧바로 닫는다 */
  onNavigate(href: string): void;
  searchRef: RefObject<HTMLInputElement | null>;
  onCollapse(): void;
  /** 이동하는 중 안내 영역을 그린다. 붙박이 사이드바와 서랍 가운데 한쪽만 그린다 */
  showStatus?: boolean;
}) {
  const { startNew } = useConversations();
  const appName = useAppName();
  const account = useShellAccount();
  const [query, setQuery] = useState("");
  // 이동하는 중인 링크의 개수다. 링크마다 하나씩 NavPending 이 두고, 이동이 끝나거나 그 링크가
  // 사라지면 줄어든다. 0보다 크면 사이드바 전체에서 무언가 이동하는 중이다.
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
          {pendingCount > 0 ? "이동하는 중" : ""}
        </span>
      ) : null}
      <div className="mb-5 flex shrink-0 items-center justify-between gap-2 px-2">
        <Link
          href="/"
          aria-label={`${appName} 홈`}
          onClick={() => {
            startNew();
            onNavigate("/");
          }}
          className="truncate text-base font-semibold"
        >
          {appName}
        </Link>
        <TooltipButton
          label="사이드바 접기"
          onClick={onCollapse}
          className="hidden hover:bg-accent md:inline-flex"
        >
          <PanelLeftClose aria-hidden="true" />
        </TooltipButton>
      </div>
      <Button
        asChild
        variant="outline"
        className="mb-2 shrink-0 justify-start gap-2 bg-background px-3 hover:bg-accent"
      >
        <Link
          href="/"
          data-testid="new-conversation-link"
          onClick={() => {
            startNew();
            onNavigate("/");
          }}
        >
          <SquarePen aria-hidden="true" />새 대화
        </Link>
      </Button>
      {/* 주요 화면 메뉴가 아니다. 대화 목록이 길어도 스크롤 없이 보이도록 「새 대화」 바로 아래에 둔다. */}
      <NowLink onNavigate={onNavigate} />
      <Input
        ref={searchRef}
        type="search"
        aria-label="대화 검색"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        placeholder="대화 검색"
        className="mb-4 shrink-0 bg-background"
      />
      <ConversationNav onNavigate={onNavigate} query={query} />
      {/* 대화 목록이 다 줄어든 뒤에 이 구역이 줄어들고 그 안에서 스크롤한다. */}
      <div className="min-h-0 shrink overflow-y-auto border-t border-border pt-3">
        <MainNav onNavigate={onNavigate} />
      </div>
      {/* 맨 아래 줄은 줄어들지 않는다. 관리자 입구가 여기 있어 화면 높이가 낮아도 보여야 한다. */}
      <div className="mt-3 flex shrink-0 items-center justify-between gap-2 px-3 py-2">
        {account.state === "failed" ? (
          <span className="flex min-w-0 items-center gap-2 text-xs text-muted-foreground">
            <span className="min-w-0 truncate">계정 정보를 읽지 못했어요</span>
            <Button
              variant="outline"
              size="sm"
              className="shrink-0 bg-background hover:bg-accent"
              onClick={account.retry}
            >
              다시 읽기
            </Button>
          </span>
        ) : account.displayName ? (
          <span
            className="min-w-0 truncate text-xs text-muted-foreground"
            title={account.displayName}
          >
            {account.displayName}
          </span>
        ) : null}
        <span className="ml-auto flex shrink-0 items-center gap-1">
          {account.state === "admin" ? (
            <Link
              href="/admin"
              data-testid="admin-entry"
              onClick={() => onNavigate("/admin")}
              className="flex items-center gap-1 rounded-md px-2 py-1.5 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              <ShieldCheck aria-hidden="true" className="size-3.5 shrink-0" />
              관리자
              <NavPending />
            </Link>
          ) : null}
          <NotificationBell onNavigate={onNavigate} />
          <ThemeToggle />
        </span>
      </div>
    </div>
  );
}
