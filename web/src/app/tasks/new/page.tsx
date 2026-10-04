import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { TaskForm } from "@/components/task/task-form";

export const metadata: Metadata = { title: "새 예약 작업" };

export default async function NewTaskPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  return (
    <div className="mx-auto w-full max-w-3xl">
      <h1 className="mb-4 text-xl font-semibold">새 예약 작업</h1>
      <TaskForm task={null} />
    </div>
  );
}
