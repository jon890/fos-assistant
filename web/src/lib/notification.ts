export type NotificationKind =
  | "APPROVAL_REQUESTED"
  | "APPROVAL_EXPIRED"
  | "TASK_SUCCEEDED"
  | "TASK_FAILED"
  | "TASK_SKIPPED";

/** 알림을 누르면 갈 곳의 종류다. 갈 곳이 없는 알림은 `null` 이다 */
export type NotificationTargetType = "CONVERSATION" | "TASK";

export type NotificationView = {
  id: string;
  kind: NotificationKind;
  title: string;
  body: string;
  targetType: NotificationTargetType | null;
  targetId: string | null;
  createdAt: string;
  /** 읽음으로 표시한 시각이다. 읽지 않았으면 null 이다 */
  readAt: string | null;
};

export type NotificationPage = {
  items: NotificationView[];
  nextCursor: string | null;
  unreadCount: number;
};

/** 사용자 단위 SSE 로 오는 사건이다. 본문 없이 읽지 않은 수만 싣는다 */
export type NotificationEvent = {
  type: "created" | "read";
  notificationId?: string;
  unreadCount: number;
};

/** 알림을 누르면 갈 주소다. 갈 곳이 없으면 null 이다 */
export function notificationHref(view: NotificationView): string | null {
  if (view.targetType === "CONVERSATION" && view.targetId) {
    return `/chat/${view.targetId}`;
  }
  if (view.targetType === "TASK" && view.targetId) {
    return `/tasks/${view.targetId}`;
  }
  return null;
}

/** 단추의 배지 글자다. 읽지 않은 알림이 없으면 null 이다 */
export function unreadBadge(count: number): string | null {
  if (count <= 0) return null;
  return count > 99 ? "99+" : String(count);
}
