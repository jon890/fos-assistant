import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { redirectMemberHome } from "@/lib/me";
import { AdminWorkspaceList } from "@/components/workspace/admin-workspace-list";

export const metadata: Metadata = { title: "파일 공간" };

export default async function WorkspaceAdminPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  await redirectMemberHome();
  return <AdminWorkspaceList />;
}
