/**
 * 알림의 서버 라우트를 부른다. 응답을 읽고 실패를 문구로 바꾸는 일은 부르는 쪽이 한다.
 */

/** 목록의 한 쪽을 읽는다. 첫 쪽이면 `cursor` 가 null 이다. */
export function fetchNotifications(
  cursor: string | null = null,
  limit: number,
): Promise<Response> {
  const query = new URLSearchParams({ limit: String(limit) });
  if (cursor !== null) query.set("cursor", cursor);
  return fetch(`/api/notifications?${query}`, { cache: "no-store" });
}

export function markNotificationRead(id: string): Promise<Response> {
  return fetch(`/api/notifications/${id}/read`, { method: "POST" });
}

export function markAllNotificationsRead(): Promise<Response> {
  return fetch("/api/notifications/read-all", { method: "POST" });
}

/** 사용자 단위 SSE 를 연다. 끝나지 않는 응답이라 `signal` 로 끊는다. */
export function openNotificationEvents(signal: AbortSignal): Promise<Response> {
  return fetch("/api/notifications/events", { cache: "no-store", signal });
}
