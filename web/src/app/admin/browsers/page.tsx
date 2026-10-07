import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { redirectMemberHome } from "@/lib/me";
import { AdminBrowserList } from "@/components/browser/admin-browser-list";

export default async function BrowserAdminPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  await redirectMemberHome();
  return <AdminBrowserList />;
}
