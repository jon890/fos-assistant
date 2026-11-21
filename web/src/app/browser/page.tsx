import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { UserBrowserPanel } from "@/components/browser/user-browser-panel";
import { screenTabId, webUrl } from "@/components/browser/screen-input";
import { PageFrame } from "@/components/ui/page-frame";
import { PAGE_HEADS } from "@/lib/page-head";

export const metadata: Metadata = { title: "내 브라우저" };

export default async function BrowserPage({
  searchParams,
}: {
  searchParams: Promise<{ url?: string | string[]; tab?: string | string[] }>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  // 시작 주소는 http, https 만 정규화해 받고 나머지는 무시한다. 처음 연 화면만 그 주소로 간다.
  const { url, tab } = await searchParams;
  const startUrl = typeof url === "string" ? webUrl(url) : null;
  // 화면 넘기기 카드가 가리킨 탭이다. 처음 연 화면에서 그 탭이 있으면 그리로 옮긴다.
  const startTab = typeof tab === "string" ? screenTabId(tab) : null;
  // 상태는 서버에서 읽지 않는다. 켜고 끄는 동안 브라우저가 다시 읽는다.
  return (
    <PageFrame {...PAGE_HEADS.browser}>
      <UserBrowserPanel startUrl={startUrl} startTab={startTab} />
    </PageFrame>
  );
}
