import { redirect } from "next/navigation";

/**
 * 전의 알림 화면 주소다. 「알림」 탭이 열린 「지금 볼 것」 으로 넘긴다. 저장해 둔 주소와 이미 보낸 알림의 링크가
 * 깨지지 않게 둔다(docs/features/app-shell/spec.md 의 DECISION15). 로그인 확인은 `/now` 가 한다.
 */
export default function NotificationsPage() {
  redirect("/now?tab=notifications");
}
