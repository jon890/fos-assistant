"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useId } from "react";
import { ArrowLeft, MessageSquare } from "lucide-react";
import { cn } from "cn";
import { useMediaQuery } from "@/components/ui/use-media-query";
import { useBackToChatHref } from "./back-to-chat";
import { NavPending } from "./nav-pending";
import { ScreenTransition } from "./screen-transition";
import {
  SETTINGS_GROUPS,
  SETTINGS_PATH,
  selectedSettingsHref,
  type SettingsGroup,
} from "./settings-area";

function SettingsGroupLinks({
  group,
  selected,
  onList,
}: {
  group: SettingsGroup;
  selected: string | null;
  onList: boolean;
}) {
  const labelId = useId();
  // `/settings` 의 넓은 화면에서는 「에이전트」 본문이 보이므로 지금 화면으로 알린다. 휴대폰 목록 화면에는 지금 화면이 없다.
  const wide = useMediaQuery("(min-width: 768px)") === true;
  return (
    <div role="group" aria-labelledby={labelId} className="flex flex-col gap-1">
      <p id={labelId} className="px-3 pt-2 pb-1 text-xs text-muted-foreground">
        {group.label}
      </p>
      {group.links.map((link) => {
        const current = link.href === selected;
        // `/settings` 의 「에이전트」 는 넓은 화면에서만 골라진 화면이다. 휴대폰의 목록 화면에는 지금 화면이 없다.
        const selectedClass = onList
          ? "md:bg-accent md:font-medium md:text-foreground"
          : "bg-accent font-medium text-foreground";
        return (
          <Link
            key={link.href}
            href={link.href}
            aria-current={current && (!onList || wide) ? "page" : undefined}
            data-selected={current ? "true" : undefined}
            className={cn(
              "flex items-center gap-2 rounded-md px-3 py-2 text-sm text-muted-foreground hover:bg-accent hover:text-foreground",
              current && selectedClass,
            )}
          >
            <link.icon aria-hidden="true" className="size-4 shrink-0" />
            {link.label}
            <NavPending />
          </Link>
        );
      })}
    </div>
  );
}

/**
 * 「설정」 화면들의 틀이다(docs/features/app-shell/design.md 의 「설정 틀의 배치」).
 *
 * <p>사이드바와 대화 목록을 그리지 않고 머리에 「대화로」 를 둔다. 넓은 화면은 왼쪽에 묶음 목록, 오른쪽에 본문이다.
 * 휴대폰은 `/settings` 에서 묶음 목록만, 다른 주소에서는 본문만 보이고 머리의 뒤로 가기가 목록으로 돌아간다.
 * 넓이는 CSS 로만 나눠 첫 그림과 hydration 사이에 배치가 바뀌지 않게 한다.
 */
export function SettingsShell({
  children,
  ready,
}: {
  children: React.ReactNode;
  ready: boolean;
}) {
  const pathname = usePathname();
  const backHref = useBackToChatHref();
  const onList = pathname === SETTINGS_PATH;
  const selected = selectedSettingsHref(pathname);

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-1 flex-col">
      <header className="flex h-14 shrink-0 items-center gap-2 border-b border-border px-4">
        {onList ? null : (
          <Link
            href={SETTINGS_PATH}
            aria-label="설정 목록으로"
            className="flex size-9 shrink-0 items-center justify-center rounded-md hover:bg-muted md:hidden"
          >
            <ArrowLeft aria-hidden="true" className="size-5" />
          </Link>
        )}
        <span className="min-w-0 flex-1 truncate text-base font-semibold">
          설정
        </span>
        <Link
          href={backHref}
          className="flex shrink-0 items-center gap-1 rounded-md px-2 py-1.5 text-sm hover:bg-muted"
        >
          <MessageSquare aria-hidden="true" className="size-4 shrink-0" />
          대화로
        </Link>
      </header>
      <div className="flex min-h-0 min-w-0 flex-1 flex-col md:flex-row">
        <nav
          aria-label="설정 메뉴"
          className={cn(
            "flex shrink-0 flex-col gap-2 overflow-y-auto bg-muted px-3 py-4 md:w-64 md:border-r md:border-border",
            onList ? "min-h-0 flex-1 md:flex-none" : "max-md:hidden",
          )}
        >
          {SETTINGS_GROUPS.map((group) => (
            <SettingsGroupLinks
              key={group.label}
              group={group}
              selected={selected}
              onList={onList}
            />
          ))}
        </nav>
        <main
          aria-busy={!ready}
          className={cn(
            "mx-auto min-h-0 w-full min-w-0 flex-1 overflow-y-auto px-4 py-5",
            onList && "max-md:hidden",
          )}
        >
          <ScreenTransition>{children}</ScreenTransition>
        </main>
      </div>
    </div>
  );
}
