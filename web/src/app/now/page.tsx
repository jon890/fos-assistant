import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { NowScreen } from "@/components/now/now-screen";
import { NowTabs } from "@/components/now/now-tabs";

export const metadata: Metadata = { title: "지금 볼 것" };

export default async function NowPage() {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }

  return (
    <div className="mx-auto w-full max-w-4xl">
      <h1 className="mb-4 text-xl font-semibold">지금 볼 것</h1>
      {/* 열린 탭은 주소의 `?tab=notifications` 가 정한다. 탭 부품이 브라우저에서 읽는다. */}
      <NowTabs todo={<NowScreen />} />
    </div>
  );
}
