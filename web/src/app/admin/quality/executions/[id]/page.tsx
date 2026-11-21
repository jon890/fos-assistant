import type { Metadata } from "next";
import { redirect } from "next/navigation";
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
      <ExecutionDetail executionId={Number(id)} source="quality" />
    </div>
  );
}
