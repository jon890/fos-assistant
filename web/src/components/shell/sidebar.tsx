"use client";

import Link from "next/link";
import { useEffect, useState, type RefObject } from "react";
import { PanelLeftClose, SquarePen } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { NotificationBell } from "@/components/notification/notification-bell";
import { AccountMenu } from "./account-menu";
import { ConversationNav } from "./conversation-nav";
import { MainNav } from "./main-nav";
import { NAV_PENDING_EVENT } from "./nav-pending";
import { NowLink } from "./now-link";
import { RoomLink } from "./room-link";
import { NEW_CONVERSATION_PATH } from "@/lib/room";
import { useAppName } from "./shell-account";
import { useConversations } from "./conversations-provider";

export function Sidebar({
  onNavigate,
  searchRef,
  onCollapse,
  showStatus = true,
  inDrawer = false,
}: {
  /** 링크를 누를 때 그 목적지를 받는다. 서랍은 목적지가 지금 경로와 같을 때만 곧바로 닫는다 */
  onNavigate(href: string): void;
  searchRef: RefObject<HTMLInputElement | null>;
  onCollapse(): void;
  /** 이동하는 중 안내 영역을 그린다. 붙박이 사이드바와 서랍 가운데 한쪽만 그린다 */
  showStatus?: boolean;
  /** 서랍 안에 그린다. 앱 이름 줄 오른쪽 끝이 서랍 닫기 단추 자리라 비워 둔다 */
  inDrawer?: boolean;
}) {
  const { startNew } = useConversations();
  const appName = useAppName();
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
      <div
        className={`mb-5 flex shrink-0 items-center justify-between gap-2 px-2 ${inDrawer ? "pr-10" : ""}`}
      >
        {/* 알림 단추는 앱 이름 바로 옆에 둔다. 「지금 볼 것」 으로 합칠 때까지 머무는 자리다(spec 의 REQ7). */}
        <span className="flex min-w-0 items-center gap-1">
          <Link
            href="/"
            aria-label={`${appName} 홈`}
            onClick={() => onNavigate("/")}
            className="truncate text-base font-semibold"
          >
            {appName}
          </Link>
          <NotificationBell onNavigate={onNavigate} />
        </span>
        <TooltipButton
          label="사이드바 접기"
          onClick={onCollapse}
          className="hidden hover:bg-accent md:inline-flex"
        >
          <PanelLeftClose aria-hidden="true" />
        </TooltipButton>
      </div>
      {/* 기본 비서와 이어지는 대화방이다. 대화 목록과 따로 맨 위에 고정한다(docs/features/agent-team.md 의 「대화방」). */}
      <RoomLink onNavigate={onNavigate} />
      <Button
        asChild
        variant="outline"
        className="mb-2 shrink-0 justify-start gap-2 bg-background px-3 hover:bg-accent"
      >
        <Link
          href={NEW_CONVERSATION_PATH}
          data-testid="new-conversation-link"
          onClick={() => {
            startNew();
            onNavigate(NEW_CONVERSATION_PATH);
          }}
        >
          <SquarePen aria-hidden="true" />새 대화
        </Link>
      </Button>
      <Input
        ref={searchRef}
        type="search"
        aria-label="대화 검색"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        placeholder="대화 검색"
        className="mb-2 shrink-0 bg-background"
      />
      {/* 화면 메뉴가 아니다. 대화 목록이 길어도 스크롤 없이 보이도록 대화 목록 위에 둔다. */}
      <NowLink onNavigate={onNavigate} />
      {/* 높이가 모자라면 대화 목록이 먼저 0 까지 줄고 그다음 이 구역이 줄며 그 안에서 스크롤한다.
          그래서 화면 높이가 낮아도 맨 아래 이름 줄이 잘리지 않는다. */}
      <div className="mb-3 min-h-0 shrink overflow-y-auto">
        <MainNav onNavigate={onNavigate} />
      </div>
      <div className="flex min-h-0 grow basis-0 flex-col border-t border-border pt-3">
        <ConversationNav onNavigate={onNavigate} query={query} />
      </div>
      {/* 맨 아래 이름 줄은 줄어들지 않는다. 설정과 관리자 입구, 로그아웃이 이 메뉴에 있다. */}
      <AccountMenu onNavigate={onNavigate} />
    </div>
  );
}
