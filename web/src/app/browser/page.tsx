import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { UserBrowserPanel } from "@/components/browser/user-browser-panel";
import { webUrl } from "@/components/browser/screen-input";

export const metadata: Metadata = { title: "내 브라우저" };

export default async function BrowserPage({
  searchParams,
}: {
  searchParams: Promise<{ url?: string | string[] }>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  // 시작 주소는 http, https 만 정규화해 받고 나머지는 무시한다. 처음 연 화면만 그 주소로 간다.
  const { url } = await searchParams;
  const startUrl = typeof url === "string" ? webUrl(url) : null;
  // 상태는 서버에서 읽지 않는다. 켜고 끄는 동안 브라우저가 다시 읽는다.
  return (
    <div className="mx-auto w-full max-w-2xl">
      <h1 className="text-xl font-semibold">내 브라우저</h1>
      <p className="mt-1 mb-4 text-sm text-muted-foreground">
        연결에 필요한 사이트에 로그인해 두는 나만의 브라우저예요.
      </p>
      <UserBrowserPanel startUrl={startUrl} />
    </div>
  );
}
