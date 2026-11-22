import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { redirectMemberHome } from "@/lib/me";
import { QualityChecks } from "@/components/admin/quality-checks";

export const metadata: Metadata = { title: "품질" };

export default async function QualityAdminPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  await redirectMemberHome();
  return <QualityChecks />;
}
