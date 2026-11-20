import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { redirectMemberHome } from "@/lib/me";
import { AdminSecretList } from "@/components/admin/admin-secret-list";

export default async function ServerSecretAdminPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  await redirectMemberHome();
  return <AdminSecretList />;
}
