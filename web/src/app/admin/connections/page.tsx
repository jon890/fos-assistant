import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { redirectMemberHome } from "@/lib/me";
import { ConnectorAdminView } from "./connector-admin-view";

export default async function ConnectorAdminPage() {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }
  await redirectMemberHome();

  return <ConnectorAdminView />;
}
