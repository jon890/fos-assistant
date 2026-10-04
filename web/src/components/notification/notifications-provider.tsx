"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  fetchNotifications,
  openNotificationEvents,
} from "@/lib/notification-api";
import type { NotificationEvent, NotificationPage } from "@/lib/notification";
import { readEventStream } from "@/lib/stream";

/** 열린 목록 화면이 첫 쪽을 다시 읽게 하는 사건의 이름이다 */
export const NOTIFICATIONS_CHANGED_EVENT = "notifications-changed";

const RECONNECT_MS = 5_000;

type NotificationsValue = {
  /** 읽지 않은 알림 수다. 사건이 알려 준 값을 그대로 쓰고 화면이 더하거나 빼지 않는다 */
  unreadCount: number;
  /** 첫 쪽을 `limit=1` 로 읽어 수를 다시 맞춘다 */
  refresh(): void;
};

const NotificationsContext = createContext<NotificationsValue>({
  unreadCount: 0,
  refresh: () => {},
});

export function useUnreadNotifications(): NotificationsValue {
  return useContext(NotificationsContext);
}

/** 수만 읽으려는 첫 쪽 읽기다. 읽지 못하면 null 이고 `ok` 가 아닌 상태는 `status` 로 알린다 */
async function readUnreadCount(): Promise<
  { ok: true; count: number } | { ok: false; status: number }
> {
  const response = await fetchNotifications(null, 1);
  if (!response.ok) return { ok: false, status: response.status };
  const page = (await response.json()) as NotificationPage;
  return { ok: true, count: page.unreadCount };
}

/**
 * 로그인한 모든 화면에서 읽지 않은 알림 수를 들고 있는다.
 *
 * <p>첫 쪽을 읽은 뒤에 사용자 단위 SSE 를 연다. 순서가 바뀌면 늦게 온 읽기 응답이 사건의 수를 덮어쓴다.
 * 연결이 끊기면 잠시 뒤 다시 읽고 다시 연다. 4xx 는 다시 열어도 같으므로 다시 열지 않는다.
 */
export function NotificationsProvider({
  enabled,
  children,
}: {
  enabled: boolean;
  children: React.ReactNode;
}) {
  const [unreadCount, setUnreadCount] = useState(0);
  const refreshing = useRef(0);

  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    let timer: number | undefined;
    let attempts = 0;
    const connect = async () => {
      let reconnect = true;
      const reconnected = attempts > 0;
      attempts += 1;
      try {
        const read = await readUnreadCount();
        if (controller.signal.aborted) return;
        if (!read.ok) {
          reconnect = read.status >= 500;
        } else {
          setUnreadCount(read.count);
          // 끊긴 사이의 사건은 다시 오지 않으므로 열린 목록도 다시 읽게 한다.
          if (reconnected) {
            window.dispatchEvent(new CustomEvent(NOTIFICATIONS_CHANGED_EVENT));
          }
          const response = await openNotificationEvents(controller.signal);
          if (response.ok) {
            await readEventStream<NotificationEvent>(response, (event) => {
              // 이 사건보다 먼저 시작한 refresh 의 응답이 이 수를 덮지 않게 버린다.
              refreshing.current += 1;
              setUnreadCount(event.unreadCount);
              window.dispatchEvent(
                new CustomEvent(NOTIFICATIONS_CHANGED_EVENT),
              );
            });
          } else {
            reconnect = response.status >= 500;
          }
        }
      } catch {
        // 연결이 깨졌다. 끊은 것이 이 화면이 아니면 아래에서 다시 연다.
      }
      if (reconnect && !controller.signal.aborted) {
        timer = window.setTimeout(() => void connect(), RECONNECT_MS);
      }
    };
    void connect();
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [enabled]);

  const refresh = useCallback(() => {
    const ticket = ++refreshing.current;
    void readUnreadCount()
      .then((read) => {
        // 더 나중에 시작한 읽기가 있거나 그 사이 사건이 왔으면 이 응답은 버린다.
        if (read.ok && ticket === refreshing.current)
          setUnreadCount(read.count);
      })
      .catch(() => {
        /* 다음 사건이나 다시 연결이 수를 맞춘다. */
      });
  }, []);

  const value = useMemo(
    () => ({ unreadCount, refresh }),
    [unreadCount, refresh],
  );
  return (
    <NotificationsContext.Provider value={value}>
      {children}
    </NotificationsContext.Provider>
  );
}
