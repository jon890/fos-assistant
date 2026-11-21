import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { auth } from "@/auth";
import { ExecutionDetail } from "@/components/execution/execution-detail";
import { redirectMemberHome } from "@/lib/me";

export const metadata: Metadata = { title: "품질 · 실행" };

/** 품질 화면의 실패 줄에서 여는 평가 전용 사용자의 실행 트리다. */
export default async function QualityExecutionPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  await redirectMemberHome();

  const { id } = await params;
  if (!/^\d+$/.test(id)) redirect("/admin/quality");

  return (
    <div className="mx-auto w-full max-w-5xl">
      {/* 실행 상세가 제목을 그리므로 공통 틀의 뒤로 가기 줄만 같은 모양으로 둔다. */}
      <Link
        href="/admin/quality"
        data-slot="page-back"
        className="mb-1 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft aria-hidden="true" className="size-4 shrink-0" />
        품질
      </Link>
      <ExecutionDetail executionId={Number(id)} source="quality" />
    </div>
  );
}
