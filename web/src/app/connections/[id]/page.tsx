import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ConnectorConnectionPanel } from "@/components/connector/connector-connection-panel";

export default async function ConnectionPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const { id } = await params;
  return <ConnectorConnectionPanel id={id} />;
}
