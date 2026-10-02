import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { UsageScreen } from "@/components/usage/usage-screen";
import { redirectMemberHome } from "@/lib/me";

export default async function AdminUsagePage({
  searchParams,
}: {
  searchParams: Promise<{ tab?: string | string[] }>;
}) {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }
  await redirectMemberHome();

  return (
    <UsageScreen
      admin={true}
      basePath="/admin/usage"
      searchParams={searchParams}
    />
  );
}
