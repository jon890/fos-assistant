import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { callControlPlane } from "@/lib/control-plane";
import type { NotificationView } from "@/lib/notification";
import { openNotification, signinPathFor } from "@/lib/push-open";

type PageProps = { params: Promise<{ notificationId: string }> };

/**
 * 푸시를 누르면 여는 주소다(docs/features/attention/spec.md 의 REQ6). 화면을 그리지 않는다.
 *
 * <p>알림을 읽음으로 바꾸고 그 알림이 가리키는 곳으로 넘긴다. 로그인이 풀렸으면 로그인 뒤 이 주소로 돌아온다.
 * `/notifications` 아래에 두지 않는다. 그 주소는 화면 틀이 다른 곳으로 넘길 수 있다(설계 「웹」).
 */
export default async function OpenNotificationPage({ params }: PageProps) {
  const { notificationId } = await params;
  const session = await auth();
  if (!session?.user?.email) redirect(signinPathFor(notificationId));
  const target = await openNotification(notificationId, async (id) => {
    const result = await callControlPlane<NotificationView>(
      `/api/v1/notifications/${id}/read`,
      { method: "POST" },
    );
    return result.ok
      ? { ok: true, data: result.data }
      : { ok: false, status: result.status };
  });
  redirect(target);
}
