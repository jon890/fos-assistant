import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { UserBrowserPanel } from "@/components/browser/user-browser-panel";

export const metadata: Metadata = { title: "내 브라우저" };

export default async function BrowserPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  // 상태는 서버에서 읽지 않는다. 켜고 끄는 동안 브라우저가 다시 읽는다.
  return (
    <div className="mx-auto w-full max-w-2xl">
      <h1 className="text-xl font-semibold">내 브라우저</h1>
      <p className="mt-1 mb-4 text-sm text-muted-foreground">
        연결에 필요한 사이트에 로그인해 두는 나만의 브라우저예요.
      </p>
      <UserBrowserPanel />
    </div>
  );
}
