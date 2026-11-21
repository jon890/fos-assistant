import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ConnectorCatalog } from "@/components/connector/connector-catalog";
import { agentCodeParam } from "@/lib/agent";

export default async function ConnectionsPage({
  searchParams,
}: {
  /** `agent` 는 에이전트 화면의 「외부 서비스 연결하기」 로 왔을 때 그 에이전트 번호다. */
  searchParams: Promise<{ agent?: string | string[] }>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const { agent } = await searchParams;
  const preferredAgent = agentCodeParam(agent);
  return <ConnectorCatalog preferredAgent={preferredAgent} />;
}
