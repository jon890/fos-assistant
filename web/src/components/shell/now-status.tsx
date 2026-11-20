"use client";

import { usePathname } from "next/navigation";
import { createContext, useContext, useEffect, useState } from "react";
import {
  NOTIFICATIONS_CHANGED_EVENT,
  useUnreadNotifications,
} from "@/components/notification/notifications-provider";
import { ATTENTION_CHANGED_EVENT, readNowCount } from "@/lib/attention-api";

type NowStatus = {
  /** 내가 할 것의 수(`nowCount`)다. 읽지 못했으면 0 이다 */
  nowCount: number;
  /** 읽지 않은 알림의 수다. `NotificationsProvider` 의 값을 그대로 넘긴다 */
  unreadCount: number;
};

const NowCountContext = createContext(0);

/** 「지금 볼 것」 줄, 휴대폰 위 막대의 단추, 탭 이름이 함께 쓰는 두 수다. 두 수를 더하지 않는다 */
export function useNowStatus(): NowStatus {
  const nowCount = useContext(NowCountContext);
  const { unreadCount } = useUnreadNotifications();
  return { nowCount, unreadCount };
}

/**
 * 할 것의 수를 한곳에서 읽어 들고 있는다. `NotificationsProvider` 안에 둔다.
 *
 * <p>경로가 바뀔 때, 지금 화면 안의 동작(`ATTENTION_CHANGED_EVENT`), 새 알림 사건이나 알림 연결을 다시 열 때
 * (`NOTIFICATIONS_CHANGED_EVENT`) 다시 읽는다. 승인 요청 알림이 왔는데 수가 그대로면 점과 숫자가 어긋나기 때문이다
 * (docs/features/app-shell/spec.md 의 DECISION20).
 */
export function NowStatusProvider({
  enabled,
  children,
}: {
  enabled: boolean;
  children: React.ReactNode;
}) {
  const pathname = usePathname();
  const [nowCount, setNowCount] = useState(0);
  useEffect(() => {
    if (!enabled) return;
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
    window.addEventListener(NOTIFICATIONS_CHANGED_EVENT, read);
    return () => {
      stale = true;
      window.removeEventListener(ATTENTION_CHANGED_EVENT, read);
      window.removeEventListener(NOTIFICATIONS_CHANGED_EVENT, read);
    };
  }, [enabled, pathname]);

  return (
    <NowCountContext.Provider value={nowCount}>
      {children}
    </NowCountContext.Provider>
  );
}
