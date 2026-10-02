import { loadAgentDetail } from "@/components/agent/agent-detail-loader";

export default async function AgentPersonaPage({
  params,
}: {
  params: Promise<{ code: string }>;
}) {
  const { code } = await params;
  return loadAgentDetail(code, { admin: false });
}
