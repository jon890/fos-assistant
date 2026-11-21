import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { Suspense } from "react";
import { auth } from "@/auth";
import { MyFiles } from "@/components/files/my-files";
import { PageFrame } from "@/components/ui/page-frame";
import { ListSkeleton } from "@/components/ui/page-state";
import { PAGE_HEADS } from "@/lib/page-head";

export const metadata: Metadata = { title: "파일 공간" };

/**
 * 「파일 공간」 이다. 탭 없이 「내 파일」 목록을 먼저 보이고, 「에이전트 작업 폴더」 는 링크로 들어간다(스펙 workspace REQ9).
 *
 * <p>폴더나 파일을 가리키는 옛 「실행 공간」 주소(`path`, `file`)는 작업 폴더의 같은 자리로 넘긴다. 옛 「보관한 결과물」 탭의 주소(`tab=vault`)는
 * `tab` 을 무시해 이 화면이 연다.
 */
export default async function FilesPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const params = await searchParams;
  const legacy = new URLSearchParams();
  for (const name of ["path", "file"]) {
    const value = params[name];
    if (typeof value === "string") legacy.set(name, value);
  }
  if (legacy.size > 0) redirect(`/files/workspace?${legacy}`);
  // 목록은 서버에서 읽지 않는다. 실패와 다시 읽기를 브라우저가 다룬다.
  return (
    <PageFrame
      {...PAGE_HEADS.files}
      action={
        <Link
          href="/files/workspace"
          prefetch={false}
          className="text-sm underline underline-offset-4"
        >
          에이전트 작업 폴더
        </Link>
      }
    >
      {/* 주소의 `item` 을 useSearchParams 로 읽으므로 Suspense 로 감싼다. */}
      <Suspense fallback={<ListSkeleton />}>
        <MyFiles />
      </Suspense>
    </PageFrame>
  );
}
