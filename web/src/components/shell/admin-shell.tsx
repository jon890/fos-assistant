"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useSyncExternalStore } from "react";
import { ArrowLeft, Bot, Cpu, Globe, Plug, Receipt, Users } from "lucide-react";
import { cn } from "cn";
import { Badge } from "@/components/ui/badge";
import { ScreenTransition } from "./screen-transition";

/** 화면 틀이 마지막으로 본 대화의 경로를 적어 두는 `sessionStorage` 의 이름이다. */
export const LAST_CONVERSATION_KEY = "last-conversation-path";

const LINKS = [
  { href: "/admin/people", label: "사용자", icon: Users },
  { href: "/admin/agents", label: "에이전트", icon: Bot },
  { href: "/admin/models", label: "모델", icon: Cpu },
  { href: "/admin/usage", label: "사용량과 비용", icon: Receipt },
  { href: "/admin/connections", label: "커넥터", icon: Plug },
  { href: "/admin/browsers", label: "브라우저", icon: Globe },
] as const;

function under(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}

/** 실행 상세는 사용량의 실행 기록에서 열므로 「사용량과 비용」 을 고른 것으로 친다. */
function selected(pathname: string, href: string): boolean {
  if (href === "/admin/usage" && under(pathname, "/admin/executions"))
    return true;
  return under(pathname, href);
}

/** 적어 둔 대화가 없거나 저장소를 읽지 못하면 새 대화 화면으로 돌아간다. */
function readBackHref(): string {
  try {
    const stored = sessionStorage.getItem(LAST_CONVERSATION_KEY);
    return stored?.startsWith("/chat/") ? stored : "/";
  } catch {
    return "/";
  }
}

// 이 탭의 `sessionStorage` 는 관리자 영역에 머무는 동안 바뀌지 않으므로 구독할 것이 없다.
function subscribeNothing(): () => void {
  return () => {};
}

/**
 * 관리자 영역(`/admin` 아래)의 틀이다. 사이드바 대신 머리와 관리자 메뉴를 그린다.
 *
 * <p>넓은 화면은 메뉴가 왼쪽에 세로로 서고, 좁은 화면은 머리 아래에서 가로로 밀리는 한 줄이다.
 */
export function AdminShell({
  children,
  ready,
}: {
  children: React.ReactNode;
  ready: boolean;
}) {
  const pathname = usePathname();
  const backHref = useSyncExternalStore(
    subscribeNothing,
    readBackHref,
    () => "/",
  );

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-1 flex-col">
      <header className="flex h-14 shrink-0 items-center justify-between gap-3 border-b border-border px-4">
        <Badge>관리자</Badge>
        <Link
          href={backHref}
          className="flex min-w-0 items-center gap-1 rounded-md px-2 py-1.5 text-sm hover:bg-muted"
        >
          <ArrowLeft aria-hidden="true" className="size-4 shrink-0" />
          <span className="truncate">사용 화면으로 돌아가기</span>
        </Link>
      </header>
      <div className="flex min-h-0 min-w-0 flex-1 flex-col md:flex-row">
        <nav
          aria-label="관리자 메뉴"
          className={cn(
            "flex shrink-0 gap-1 overflow-x-auto border-b border-border bg-muted px-3 py-2",
            "md:w-56 md:flex-col md:overflow-x-visible md:overflow-y-auto md:border-r md:border-b-0 md:py-4",
          )}
        >
          {LINKS.map((link) => {
            const current = selected(pathname, link.href);
            return (
              <Link
                key={link.href}
                href={link.href}
                aria-current={current ? "page" : undefined}
                className={cn(
                  "flex shrink-0 items-center gap-2 rounded-md px-3 py-2 text-sm whitespace-nowrap hover:bg-accent hover:text-foreground",
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
        </nav>
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
