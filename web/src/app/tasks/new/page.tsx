import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { TaskForm } from "@/components/task/task-form";
import { PageFrame } from "@/components/ui/page-frame";
import { PAGE_HEADS } from "@/lib/page-head";

export const metadata: Metadata = { title: "새 예약 작업" };

export default async function NewTaskPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  return (
    <PageFrame
      title="새 예약 작업"
      back={{ href: "/tasks", label: PAGE_HEADS.tasks.title }}
    >
      <TaskForm task={null} />
    </PageFrame>
  );
}
