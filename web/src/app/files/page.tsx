import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { Suspense } from "react";
import { auth } from "@/auth";
import { WorkspaceExplorer } from "@/components/workspace/workspace-explorer";

export const metadata: Metadata = { title: "파일 공간" };

export default async function FilesPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  // 상태와 목록은 서버에서 읽지 않는다. 실패와 다시 읽기를 브라우저가 다룬다.
  // 폭은 목록 옆에 미리보기 패널이 붙도록 넓게 둔다.
  return (
    <div className="mx-auto w-full max-w-6xl">
      <h1 className="mb-2 text-xl font-semibold">파일 공간</h1>
      {/* 주소의 `path`, `file` 을 useSearchParams 로 읽으므로 Suspense 로 감싼다. */}
      <Suspense
        fallback={<p className="text-sm text-muted-foreground">불러오는 중…</p>}
      >
        <WorkspaceExplorer />
      </Suspense>
    </div>
  );
}
