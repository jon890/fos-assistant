import { NextResponse } from "next/server";
import {
  connectionResponse,
  connectorCall,
  invalidConnectionRequest,
  isConnectorId,
} from "@/lib/connection-route";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import type { AgentConnectionView } from "@/lib/agent-connection";

type Context = { params: Promise<{ code: string; connectorId: string }> };

async function target(context: Context) {
  const { code, connectorId } = await context.params;
  return AGENT_CODE_PATTERN.test(code) && isConnectorId(connectorId)
    ? `/api/v1/agents/${code}/connections/${connectorId}`
    : null;
}

/** 내 연결을 그 에이전트에 붙인다. 요청 본문은 받지 않는다. */
export async function PUT(_: Request, context: Context) {
  const path = await target(context);
  if (path === null) return invalidConnectionRequest();
  return connectionResponse(
    await connectorCall<AgentConnectionView>(path, "agentConnection", {
      method: "PUT",
    }),
  );
}

/** 그 에이전트에서 연결을 뗀다. 성공하면 본문 없이 204 다. */
export async function DELETE(_: Request, context: Context) {
  const path = await target(context);
  if (path === null) return invalidConnectionRequest();
  const result = await connectorCall<null>(path, "none", { method: "DELETE" });
  if (!result.ok) return connectionResponse(result);
  return new NextResponse(null, { status: 204 });
}
