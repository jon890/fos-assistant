import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { AccountbookAdminPanel } from "@/components/connector/accountbook-admin-panel";
import { AccountbookConnectionPanel } from "@/components/connector/accountbook-connection-panel";
import type { AccountbookConnection, AdminAccountbookConnection } from "@/lib/connection";
import { connectorCall } from "@/lib/connection-route";
import { readMe } from "@/lib/me";

export default async function AccountbookConnectionPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const [connectionResult, me] = await Promise.all([connectorCall<AccountbookConnection>("/api/v1/connections/accountbook"), readMe()]);
  const adminResult = me?.role === "ADMIN"
    ? await connectorCall<AdminAccountbookConnection[]>("/api/v1/admin/connections/accountbook") : null;
  return <div className="mx-auto w-full max-w-2xl">
    <AccountbookConnectionPanel initialConnection={connectionResult.ok ? connectionResult.data : null} />
    {me?.role === "ADMIN" ? <AccountbookAdminPanel initialConnections={adminResult?.ok ? adminResult.data : []} /> : null}
  </div>;
}
