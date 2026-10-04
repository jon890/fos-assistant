import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { TaskList } from "@/components/task/task-list";

export const metadata: Metadata = { title: "예약 작업" };

export default async function TasksPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  // 목록은 서버에서 읽지 않는다. 브라우저가 화면을 연 뒤 읽는다.
  return <TaskList />;
}
