import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ConnectorConnectionPanel } from "@/components/connector/connector-connection-panel";
import { agentCodeParam } from "@/lib/agent";

export default async function ConnectionPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  /** `agent` 는 에이전트 화면에서 이 연결을 하러 왔을 때 그 에이전트 번호다. */
  searchParams: Promise<{ agent?: string | string[] }>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const { id } = await params;
  const { agent } = await searchParams;
  const preferredAgent = agentCodeParam(agent);
  return <ConnectorConnectionPanel id={id} preferredAgent={preferredAgent} />;
}
