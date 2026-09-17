"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { SiteNav } from "@/components/ui/site-nav";
import { ThemeToggle } from "@/components/ui/theme-toggle";

type Props = {
  isAdmin: boolean;
  displayName?: string;
};

export function SiteHeader({ isAdmin, displayName }: Props) {
  const pathname = usePathname();
  // RootLayout은 요청 경로를 안정적으로 받지 못하므로 경로를 아는 자식이 로그인 화면의 메뉴를 숨긴다.
  const showNavigation = pathname !== "/signin";

  return (
    <header className="flex shrink-0 flex-nowrap items-center gap-2 border-b border-border px-3 py-2 text-sm sm:gap-4 sm:px-4">
      <Link href="/" className="shrink-0 font-semibold" aria-label="우리집 비서 홈">
        <span className="flex h-8 w-8 items-center justify-center rounded-full bg-brand text-[0px] text-on-brand before:text-sm before:content-['우'] sm:h-auto sm:w-auto sm:bg-transparent sm:text-sm sm:text-foreground sm:before:content-none">
          우리집 비서
        </span>
      </Link>
      {showNavigation ? <SiteNav isAdmin={isAdmin} /> : null}
      <div className="ml-auto flex min-w-0 shrink-0 items-center gap-2">
        {showNavigation && displayName ? (
          <span className="max-w-16 truncate text-xs text-muted sm:max-w-40" title={displayName}>
            {displayName}
          </span>
        ) : null}
        <ThemeToggle />
      </div>
    </header>
  );
}
