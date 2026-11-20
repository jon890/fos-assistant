"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  ArrowLeft,
  Bot,
  Cpu,
  Globe,
  HardDrive,
  LayoutDashboard,
  Menu,
  Plug,
  Receipt,
  Users,
  Wrench,
} from "lucide-react";
import { cn } from "cn";
import { Badge } from "@/components/ui/badge";
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet";
import { TooltipButton } from "@/components/ui/tooltip-button";
import { useMediaQuery } from "@/components/ui/use-media-query";
import { useBackToChatHref } from "./back-to-chat";
import { ScreenTransition } from "./screen-transition";

/**
 * 관리자 메뉴의 묶음과 줄이다. 순서는 `docs/features/admin-area/spec.md` 의 REQ1 이 정한다.
 *
 * <p>품질과 기억 줄은 그 화면을 만드는 PR 이 「살펴보기」 의 두 번째와 「비서 설정」 의 마지막에 더한다.
 */
const GROUPS = [
  {
    id: "overview",
    label: "살펴보기",
    links: [
      { href: "/admin", label: "요약", icon: LayoutDashboard },
      { href: "/admin/usage", label: "사용량과 비용", icon: Receipt },
    ],
  },
  {
    id: "assistant",
    label: "비서 설정",
    links: [
      { href: "/admin/agents", label: "에이전트", icon: Bot },
      { href: "/admin/models", label: "모델", icon: Cpu },
      { href: "/admin/tools", label: "도구", icon: Wrench },
    ],
  },
  {
    id: "resources",
    label: "사용자와 자원",
    links: [
      { href: "/admin/people", label: "사용자", icon: Users },
      { href: "/admin/connections", label: "외부 서비스 연결", icon: Plug },
      { href: "/admin/browsers", label: "브라우저", icon: Globe },
      { href: "/admin/workspaces", label: "파일 공간", icon: HardDrive },
    ],
  },
] as const;

function under(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}

/**
 * 지금 경로에서 골라진 줄인가.
 *
 * <p>「요약」(`/admin`)은 주소가 같을 때만 고른다. 하위 주소까지 고르면 모든 관리자 화면에서 함께 골라진다.
 * 실행 상세는 사용량의 실행 기록에서 열므로 「사용량과 비용」 을 고른 것으로 친다.
 */
export function selected(pathname: string, href: string): boolean {
  if (href === "/admin") return pathname === "/admin";
  if (href === "/admin/usage" && under(pathname, "/admin/executions"))
    return true;
  return under(pathname, href);
}

/** 묶음 제목과 줄들이다. 넓은 화면의 세로 메뉴와 휴대폰 서랍이 같은 것을 그린다. */
function AdminMenu({
  pathname,
  onNavigate,
}: {
  pathname: string;
  onNavigate?: () => void;
}) {
  return (
    <nav aria-label="관리자 메뉴" className="flex flex-col gap-4">
      {GROUPS.map((group) => (
        <section
          key={group.id}
          aria-labelledby={`admin-menu-${group.id}`}
          className="flex flex-col gap-1"
        >
          <p
            id={`admin-menu-${group.id}`}
            className="px-3 text-xs font-medium text-muted-foreground"
          >
            {group.label}
          </p>
          {group.links.map((link) => {
            const current = selected(pathname, link.href);
            return (
              <Link
                key={link.href}
                href={link.href}
                onClick={onNavigate}
                aria-current={current ? "page" : undefined}
                className={cn(
                  "flex items-center gap-2 rounded-md px-3 py-2 text-sm whitespace-nowrap hover:bg-accent hover:text-foreground",
                  current
                    ? "bg-accent font-medium text-foreground"
                    : "text-muted-foreground",
                )}
              >
                <link.icon aria-hidden="true" className="size-4 shrink-0" />
                {link.label}
              </Link>
            );
          })}
        </section>
      ))}
    </nav>
  );
}

/**
 * 관리자 영역(`/admin` 아래)의 틀이다. 사이드바 대신 머리와 관리자 메뉴를 그린다.
 *
 * <p>넓은 화면은 메뉴가 왼쪽에 세로로 선다. 좁은 화면은 머리 맨 왼쪽의 단추로 같은 메뉴를 서랍으로 연다.
 */
export function AdminShell({
  children,
  ready,
}: {
  children: React.ReactNode;
  ready: boolean;
}) {
  const pathname = usePathname();
  const backHref = useBackToChatHref();
  const wide = useMediaQuery("(min-width: 768px)");
  const [drawerOpen, setDrawerOpen] = useState(false);
  // 서랍이 열린 채 넓은 폭이 되면 닫힌 것으로 그린다. 다시 좁혀도 저절로 열리지 않게 넓어진 순간에 닫아 둔다.
  const [seenWide, setSeenWide] = useState(wide);
  if (seenWide !== wide) {
    setSeenWide(wide);
    if (wide) setDrawerOpen(false);
  }
  const drawerShown = drawerOpen && wide !== true;

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-1 flex-col">
      <header className="flex h-14 shrink-0 items-center justify-between gap-3 border-b border-border px-4">
        <div className="flex min-w-0 items-center gap-2">
          <TooltipButton
            label="관리자 메뉴 열기"
            size="icon"
            className="md:hidden"
            onClick={() => setDrawerOpen(true)}
          >
            <Menu aria-hidden="true" className="size-5" />
          </TooltipButton>
          <Badge>관리자</Badge>
        </div>
        <Link
          href={backHref}
          className="flex min-w-0 items-center gap-1 rounded-md px-2 py-1.5 text-sm hover:bg-muted"
        >
          <ArrowLeft aria-hidden="true" className="size-4 shrink-0" />
          <span className="truncate">사용 화면으로 돌아가기</span>
        </Link>
      </header>
      <Sheet open={drawerShown} onOpenChange={setDrawerOpen}>
        <SheetContent
          side="left"
          closeLabel="관리자 메뉴 닫기"
          className="gap-0 bg-muted px-3 py-4 data-[side=left]:w-72 data-[side=left]:sm:max-w-72"
        >
          <SheetTitle className="sr-only">관리자 메뉴</SheetTitle>
          <AdminMenu
            pathname={pathname}
            onNavigate={() => setDrawerOpen(false)}
          />
        </SheetContent>
      </Sheet>
      <div className="flex min-h-0 min-w-0 flex-1">
        {/* 좁은 폭에서는 CSS 가 숨긴다. 서랍이 열린 동안은 같은 메뉴가 두 벌 생기지 않게 그리지 않는다. */}
        {drawerShown ? null : (
          <div className="hidden w-56 shrink-0 overflow-y-auto border-r border-border bg-muted px-3 py-4 md:block">
            <AdminMenu pathname={pathname} />
          </div>
        )}
        <main
          aria-busy={!ready}
          className="mx-auto min-h-0 w-full min-w-0 flex-1 overflow-y-auto px-4 py-5"
        >
          <ScreenTransition>{children}</ScreenTransition>
        </main>
      </div>
    </div>
  );
}
