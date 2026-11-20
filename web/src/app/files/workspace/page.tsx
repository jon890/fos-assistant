import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { Suspense } from "react";
import { auth } from "@/auth";
import { WorkspaceExplorer } from "@/components/workspace/workspace-explorer";

export const metadata: Metadata = { title: "에이전트 작업 폴더" };

/**
 * 「에이전트 작업 폴더」 다. 전의 「실행 공간」 이고 에이전트의 작업 파일이 있다. 「내 파일」 뒤에 둔다(스펙 workspace REQ9).
 *
 * <p>연 폴더와 미리 보는 파일은 주소의 `path`, `file` 이 정한다.
 */
export default async function WorkspaceFolderPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  return (
    <div className="mx-auto w-full max-w-6xl">
      <Link
        href="/files"
        prefetch={false}
        className="text-sm underline underline-offset-4"
      >
        ← 내 파일
      </Link>
      <h1 className="mt-1 mb-3 text-xl font-semibold">에이전트 작업 폴더</h1>
      {/* 주소의 `path`, `file` 을 useSearchParams 로 읽으므로 Suspense 로 감싼다. */}
      <Suspense
        fallback={<p className="text-sm text-muted-foreground">불러오는 중…</p>}
      >
        <WorkspaceExplorer />
      </Suspense>
    </div>
  );
}
