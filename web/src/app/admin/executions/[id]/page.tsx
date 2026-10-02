import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ExecutionDetail } from "@/components/execution/execution-detail";

export default async function AdminExecutionPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }

  const { id } = await params;
  if (!/^\d+$/.test(id)) {
    redirect("/admin/usage");
  }

  return (
    <div className="mx-auto w-full max-w-5xl">
      <ExecutionDetail executionId={Number(id)} />
    </div>
  );
}
