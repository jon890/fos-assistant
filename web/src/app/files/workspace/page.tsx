import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { Suspense } from "react";
import { auth } from "@/auth";
import { PageFrame } from "@/components/ui/page-frame";
import { ListSkeleton } from "@/components/ui/page-state";
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
    <PageFrame
      title="에이전트 작업 폴더"
      back={{ href: "/files", label: "내 파일" }}
    >
      {/* 주소의 `path`, `file` 을 useSearchParams 로 읽으므로 Suspense 로 감싼다. */}
      <Suspense fallback={<ListSkeleton />}>
        <WorkspaceExplorer />
      </Suspense>
    </PageFrame>
  );
}
