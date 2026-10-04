"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { describeFailure } from "@/components/error-message";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Notice } from "@/components/ui/notice";
import { Skeleton } from "@/components/ui/skeleton";
import {
  fetchNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from "@/lib/notification-api";
import {
  notificationHref,
  type NotificationPage,
  type NotificationView,
} from "@/lib/notification";
import {
  NOTIFICATIONS_CHANGED_EVENT,
  useUnreadNotifications,
} from "./notifications-provider";

/** 한 번에 읽는 알림 수다 */
const PAGE_SIZE = 20;

function formatCreatedAt(createdAt: string): string {
  return new Date(createdAt).toLocaleString("ko-KR", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** 내 알림 목록이다. 첫 쪽은 화면이 열린 뒤 브라우저가 읽는다. */
export function NotificationList() {
  const router = useRouter();
  const { unreadCount, refresh } = useUnreadNotifications();
  const [items, setItems] = useState<NotificationView[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /**
   * 첫 쪽을 읽기 시작한 차례다. 더 나중에 시작한 첫 쪽 읽기가 있으면 이전 응답은 버린다.
   * 그보다 먼저 시작한 다음 쪽 읽기의 응답도 버린다
   */
  const firstPageTicket = useRef(0);
  /** 첫 쪽을 한 번이라도 읽어 목록이 이어 읽을 자리를 갖고 있는지다 */
  const loadedOnce = useRef(false);

  const loadFirstPage = useCallback(() => {
    const ticket = ++firstPageTicket.current;
    const read = async (): Promise<NotificationPage | string> => {
      try {
        const response = await fetchNotifications(null, PAGE_SIZE);
        if (!response.ok) return await describeFailure(response);
        return (await response.json()) as NotificationPage;
      } catch {
        return "알림을 읽지 못했어요. 잠시 뒤 다시 시도해 주세요.";
      }
    };
    void read().then((result) => {
      if (ticket !== firstPageTicket.current) return;
      setLoading(false);
      if (typeof result === "string") {
        setError(result);
        return;
      }
      setError(null);
      // 처음 읽었으면 그대로 쓴다. 다시 읽었으면 이미 내려 읽은 목록을 두고 첫 쪽을 앞에 합친다.
      // 같은 알림은 새로 읽은 값을 쓴다. 이어 읽을 자리는 목록 끝의 것이라 그대로 둔다.
      if (!loadedOnce.current) {
        loadedOnce.current = true;
        setItems(result.items);
        setNextCursor(result.nextCursor);
        return;
      }
      setItems((current) => {
        const fresh = new Set(result.items.map((item) => item.id));
        return [
          ...result.items,
          ...current.filter((item) => !fresh.has(item.id)),
        ];
      });
    });
  }, []);

  useEffect(() => {
    loadFirstPage();
    window.addEventListener(NOTIFICATIONS_CHANGED_EVENT, loadFirstPage);
    return () =>
      window.removeEventListener(NOTIFICATIONS_CHANGED_EVENT, loadFirstPage);
  }, [loadFirstPage]);

  const loadMore = useCallback(async () => {
    if (nextCursor === null || loadingMore) return;
    const ticket = firstPageTicket.current;
    setLoadingMore(true);
    try {
      const response = await fetchNotifications(nextCursor, PAGE_SIZE);
      if (!response.ok) {
        setError(await describeFailure(response));
        return;
      }
      const page = (await response.json()) as NotificationPage;
      // 그 사이 첫 쪽을 다시 읽기 시작했으면 버린다. 이어 읽을 자리는 그대로라 다음에 다시 읽는다.
      if (ticket !== firstPageTicket.current) return;
      setItems((current) => {
        const known = new Set(current.map((item) => item.id));
        return [
          ...current,
          ...page.items.filter((item) => !known.has(item.id)),
        ];
      });
      setNextCursor(page.nextCursor);
    } catch {
      setError("알림을 읽지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    } finally {
      setLoadingMore(false);
    }
  }, [nextCursor, loadingMore]);

  // 목록 끝에 닿으면 다음 쪽을 읽는다.
  const sentinel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const target = sentinel.current;
    if (!target || nextCursor === null) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void loadMore();
    });
    observer.observe(target);
    return () => observer.disconnect();
  }, [nextCursor, loadMore]);

  const open = useCallback(
    async (item: NotificationView) => {
      const href = notificationHref(item);
      if (item.readAt === null) {
        try {
          const response = await markNotificationRead(item.id);
          if (response.ok) {
            const read = (await response.json()) as NotificationView;
            setItems((current) =>
              current.map((entry) => (entry.id === read.id ? read : entry)),
            );
          } else {
            setError(await describeFailure(response));
            return;
          }
        } catch {
          setError("알림을 읽음으로 표시하지 못했어요. 다시 시도해 주세요.");
          return;
        }
        refresh();
      }
      if (href !== null) router.push(href);
    },
    [refresh, router],
  );

  const readAll = useCallback(async () => {
    try {
      const response = await markAllNotificationsRead();
      if (!response.ok) {
        setError(await describeFailure(response));
        return;
      }
      const now = new Date().toISOString();
      setItems((current) =>
        current.map((item) => ({ ...item, readAt: item.readAt ?? now })),
      );
      refresh();
    } catch {
      setError("알림을 읽음으로 표시하지 못했어요. 다시 시도해 주세요.");
    }
  }, [refresh]);

  const hasUnread =
    unreadCount > 0 || items.some((item) => item.readAt === null);

  return (
    <div className="mx-auto w-full max-w-3xl">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h1 className="text-xl font-semibold">알림</h1>
        <Button
          variant="outline"
          size="sm"
          disabled={!hasUnread}
          onClick={() => void readAll()}
        >
          모두 읽음
        </Button>
      </div>
      {error ? (
        <Notice variant="error" className="mb-4">
          {error}
        </Notice>
      ) : null}
      {loading ? (
        <div
          className="flex flex-col gap-2"
          data-testid="notification-skeleton"
        >
          <Skeleton className="h-16" />
          <Skeleton className="h-16" />
          <Skeleton className="h-16" />
        </div>
      ) : items.length === 0 && !error ? (
        <EmptyState
          title="아직 알림이 없어요."
          description="승인이 필요한 요청이 생기면 여기에 알려 드려요."
        />
      ) : (
        <ul className="flex flex-col gap-2">
          {items.map((item) => (
            <li key={item.id}>
              <button
                type="button"
                data-testid="notification-item"
                data-read={item.readAt !== null}
                onClick={() => void open(item)}
                className="flex w-full items-start gap-3 rounded-md border border-border px-4 py-3 text-left hover:bg-accent"
              >
                <span
                  aria-hidden="true"
                  className={
                    item.readAt === null
                      ? "mt-1.5 size-2 shrink-0 rounded-full bg-foreground"
                      : "mt-1.5 size-2 shrink-0 rounded-full border border-border"
                  }
                />
                <span className="min-w-0 flex-1">
                  <span
                    className={
                      item.readAt === null
                        ? "block text-sm font-semibold"
                        : "block text-sm text-muted-foreground"
                    }
                  >
                    {item.title}
                    {item.readAt === null ? (
                      <span className="sr-only"> (읽지 않음)</span>
                    ) : null}
                  </span>
                  <span className="mt-0.5 block text-sm break-words text-muted-foreground">
                    {item.body}
                  </span>
                </span>
                <time
                  dateTime={item.createdAt}
                  className="shrink-0 text-xs text-muted-foreground"
                >
                  {formatCreatedAt(item.createdAt)}
                </time>
              </button>
            </li>
          ))}
        </ul>
      )}
      {nextCursor !== null ? (
        <div ref={sentinel} className="h-8" aria-hidden="true" />
      ) : null}
    </div>
  );
}
