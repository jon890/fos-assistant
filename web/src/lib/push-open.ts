import { isPublicId } from "./conversation-id.ts";
import { notificationHref, type NotificationView } from "./notification.ts";

/**
 * 푸시를 누르면 여는 `/n/<id>` 의 판단이다(docs/features/attention/spec.md 의 REQ6, 설계 DECISION7).
 *
 * <p>node 테스트가 이 파일을 바로 읽으므로 상대 경로로 import 한다(`eslint.config.mjs` 의 `NODE_TEST_READ_FILES`).
 */

/** 갈 곳이 없거나 읽음 표시가 실패했을 때 여는 곳이다. 「지금 볼 것」 의 알림 목록이다. */
export const NOTIFICATION_LIST_PATH = "/now?tab=notifications";

/** 읽음 표시 결과다. `callControlPlane` 의 결과와 모양이 같다. */
export type MarkReadResult =
  { ok: true; data: NotificationView } | { ok: false; status: number };

/** 읽음 표시 결과에서 갈 곳을 정한다. */
export function openTarget(result: MarkReadResult): string {
  if (!result.ok) return NOTIFICATION_LIST_PATH;
  return notificationHref(result.data) ?? NOTIFICATION_LIST_PATH;
}

/**
 * 그 알림을 읽음으로 바꾸고 갈 곳을 돌려준다. 웹 알림을 누른 것과 같다(스펙 DECISION10).
 *
 * <p>공개 식별자 모양이 아니면 읽음을 부르지 않는다.
 */
export async function openNotification(
  notificationId: string,
  markRead: (notificationId: string) => Promise<MarkReadResult>,
): Promise<string> {
  if (!isPublicId(notificationId)) return NOTIFICATION_LIST_PATH;
  return openTarget(await markRead(notificationId));
}

/** 로그인이 풀린 기기에서 누르면 로그인 뒤 이 열기 주소로 돌아오게 하는 로그인 주소다(스펙 DECISION23). */
export function signinPathFor(notificationId: string): string {
  return `/signin?next=${encodeURIComponent(`/n/${notificationId}`)}`;
}

/**
 * 로그인 뒤 돌아갈 주소로 받을 수 있는 같은 사이트 경로만 돌려준다. 아니면 첫 화면이다.
 *
 * <p>`value` 는 Next 가 검색어에서 이미 디코딩한 값이다. 다시 디코딩하지 않는다. 한 번 더 풀면 `%252F%252F` 처럼
 * 두 번 인코딩한 값이 검사를 지난 뒤 `//` 가 될 수 있다. `/` 로 시작하고 `//`, `\`, 제어 문자가 없어야 한다.
 * 다른 출처로 보내는 길을 막는다. Auth.js 의 기본 `redirect` 콜백도 다른 출처를 막으므로 두 겹이다.
 */
export function safeNextPath(value: string | string[] | undefined): string {
  if (typeof value !== "string") return "/";
  if (!value.startsWith("/") || value.startsWith("//")) return "/";
  if (value.includes("\\")) return "/";
  // 제어 문자(U+0000-U+001F, U+007F)가 섞이면 브라우저가 지우고 해석해 `//` 처럼 읽힐 수 있다.
  for (const char of value) {
    const code = char.charCodeAt(0);
    if (code < 0x20 || code === 0x7f) return "/";
  }
  return value;
}
