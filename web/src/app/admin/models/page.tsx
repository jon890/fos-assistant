import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ModelAdminPanel } from "@/components/admin/model-admin-panel";

export default async function ModelAdminPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  return <ModelAdminPanel />;
}
