import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { PushSettings } from "@/components/notification/push-settings";
import { appName } from "@/lib/app-name";

export const metadata: Metadata = { title: "알림" };

/**
 * 설정의 「알림」 화면이다(docs/features/attention/spec.md 의 REQ1-REQ3). 이 기기에서 웹 푸시를 받을지 정하고 내 기기를 본다.
 */
export default async function NotificationSettingsPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  // 상태는 서버에서 읽지 않는다. 브라우저 구독과 허락 상태를 브라우저만 안다.
  return (
    <div className="w-full">
      <h1 className="text-xl font-semibold">알림</h1>
      <p className="mt-1 mb-4 text-sm text-muted-foreground">
        웹을 열어 두지 않아도 이 기기에서 비서의 알림을 받아요.
      </p>
      <PushSettings appName={appName()} />
    </div>
  );
}
