"use client";

import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { NotificationList } from "@/components/notification/notification-list";
import { useNowStatus } from "@/components/shell/now-status";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { unreadBadge } from "@/lib/notification";

type NowTab = "todo" | "notifications";

/** 주소의 `tab` 값으로 열 탭을 정한다. `notifications` 가 아니면 「할 것」 이다 */
function nowTabOf(value: string | undefined): NowTab {
  return value === "notifications" ? "notifications" : "todo";
}

function TabCount({ text, spoken }: { text: string | null; spoken: string }) {
  if (text === null) return null;
  return (
    <>
      <span
        aria-hidden="true"
        className="rounded-full bg-muted px-1.5 text-xs text-foreground"
      >
        {text}
      </span>
      <span className="sr-only">{spoken}</span>
    </>
  );
}

/**
 * 「지금 볼 것」 의 「할 것」 과 「알림」 탭이다. 탭 이름 옆에 두 수를 따로 보이고 더하지 않는다.
 *
 * <p>열린 탭은 주소의 `tab` 값이 정한다. 탭을 바꾸면 `history.replaceState` 로 주소만 바꾸고, Next 가 그 값을
 * `useSearchParams` 에 반영한다. 서버 화면을 다시 받지 않고, 새로 고치거나 사이드바에서 `/now` 로 와도 주소대로 열린다.
 * 「할 것」 은 서버가 이미 그렸으므로 늘 올라와 있다. 「알림」 목록은 그 탭을 처음 열 때 올라와 첫 쪽을 읽고,
 * 그 뒤에는 탭을 오가도 내려가지 않는다(docs/features/app-shell/design.md 의 「지금 볼 것 탭」).
 */
export function NowTabs({
  todo,
}: {
  /** 서버가 그린 「할 것」 탭의 내용이다 */
  todo: React.ReactNode;
}) {
  const { nowCount, unreadCount } = useNowStatus();
  const tab = nowTabOf(useSearchParams().get("tab") ?? undefined);
  const [notificationsOpened, setNotificationsOpened] = useState(false);
  // 「알림」 탭이 한 번 열리면 목록을 올린 채 둔다. 그리는 중에 바꿔 첫 그림부터 목록이 있다.
  if (tab === "notifications" && !notificationsOpened) {
    setNotificationsOpened(true);
  }

  const change = (value: string) => {
    const url = new URL(window.location.href);
    if (nowTabOf(value) === "notifications")
      url.searchParams.set("tab", "notifications");
    else url.searchParams.delete("tab");
    window.history.replaceState(null, "", url);
  };

  const unread = unreadBadge(unreadCount);
  return (
    <Tabs value={tab} onValueChange={change}>
      <TabsList aria-label="지금 볼 것">
        <TabsTrigger value="todo" data-testid="now-tab-todo">
          할 것
          <TabCount
            text={nowCount > 0 ? String(nowCount) : null}
            spoken={`${nowCount}건`}
          />
        </TabsTrigger>
        <TabsTrigger value="notifications" data-testid="now-tab-notifications">
          알림
          <TabCount text={unread} spoken={`읽지 않음 ${unreadCount}개`} />
        </TabsTrigger>
      </TabsList>
      <TabsContent value="todo" forceMount>
        {todo}
      </TabsContent>
      <TabsContent value="notifications" forceMount>
        {notificationsOpened ? <NotificationList /> : null}
      </TabsContent>
    </Tabs>
  );
}
