import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { Suspense } from "react";
import { auth } from "@/auth";
import { VaultListView } from "@/components/files/vault-list";
import { WorkspaceExplorer } from "@/components/workspace/workspace-explorer";

export const metadata: Metadata = { title: "파일 공간" };

const TAB_CLASS = "border-b-2 px-3 py-2 text-sm font-medium";

export default async function FilesPage({
  searchParams,
}: {
  searchParams: Promise<{ tab?: string }>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const vault = (await searchParams).tab === "vault";
  // 상태와 목록은 서버에서 읽지 않는다. 실패와 다시 읽기를 브라우저가 다룬다.
  // 폭은 목록 옆에 미리보기 패널이 붙도록 넓게 둔다.
  return (
    <div className="mx-auto w-full max-w-6xl">
      <h1 className="mb-2 text-xl font-semibold">파일 공간</h1>
      <nav
        aria-label="파일 종류"
        className="mb-3 flex gap-1 border-b border-border"
      >
        <Link
          href="/files"
          aria-current={vault ? undefined : "page"}
          className={`${TAB_CLASS} ${vault ? "border-transparent text-muted-foreground" : "border-primary"}`}
        >
          실행 공간
        </Link>
        <Link
          href="/files?tab=vault"
          aria-current={vault ? "page" : undefined}
          className={`${TAB_CLASS} ${vault ? "border-primary" : "border-transparent text-muted-foreground"}`}
        >
          보관한 결과물
        </Link>
      </nav>
      {vault ? (
        <VaultListView />
      ) : (
        // 주소의 `path`, `file` 을 useSearchParams 로 읽으므로 Suspense 로 감싼다.
        <Suspense
          fallback={
            <p className="text-sm text-muted-foreground">불러오는 중…</p>
          }
        >
          <WorkspaceExplorer />
        </Suspense>
      )}
    </div>
  );
}
