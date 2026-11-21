import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { NowScreen } from "@/components/now/now-screen";
import { NowTabs } from "@/components/now/now-tabs";
import { PageFrame } from "@/components/ui/page-frame";
import { PAGE_HEADS } from "@/lib/page-head";

export const metadata: Metadata = { title: "지금 볼 것" };

export default async function NowPage() {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }

  return (
    <PageFrame {...PAGE_HEADS.now}>
      {/* 열린 탭은 주소의 `?tab=notifications` 가 정한다. 탭 부품이 브라우저에서 읽는다. */}
      <NowTabs todo={<NowScreen />} />
    </PageFrame>
  );
}
