import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ConnectorCatalog } from "@/components/connector/connector-catalog";

export default async function ConnectionsPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  return <ConnectorCatalog />;
}
