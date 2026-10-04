import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { TaskDetail } from "@/components/task/task-detail";

export const metadata: Metadata = { title: "예약 작업" };

export default async function TaskPage({
  params,
}: {
  params: Promise<{ taskId: string }>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const { taskId } = await params;
  return <TaskDetail taskId={taskId} />;
}
