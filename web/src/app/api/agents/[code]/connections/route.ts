import {
  connectionResponse,
  connectorCall,
  invalidConnectionRequest,
} from "@/lib/connection-route";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import type { AgentConnectionsList } from "@/lib/agent-connection";

/** 그 에이전트에서 본 내 연결들이다. 주인만 읽는다. */
export async function GET(
  _: Request,
  { params }: { params: Promise<{ code: string }> },
) {
  const { code } = await params;
  if (!AGENT_CODE_PATTERN.test(code)) return invalidConnectionRequest();
  return connectionResponse(
    await connectorCall<AgentConnectionsList>(
      `/api/v1/agents/${code}/connections`,
      "agentConnections",
    ),
  );
}
