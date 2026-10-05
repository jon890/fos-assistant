import {
  connectionResponse,
  connectorCall,
  invalidConnectionRequest,
  isConnectorId,
} from "@/lib/connection-route";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import type { AgentConnectionView } from "@/lib/agent-connection";
import { readJsonBody } from "@/lib/json-body";

/**
 * 관리자가 공유 gateway 를 재시작한 뒤 그 바인딩의 반영 완료를 누른다.
 *
 * <p>본문의 `restartRequiredSince` 는 관리자 목록에서 받은 값 그대로다. 그 뒤에 다시 설치됐으면 Control Plane 이 거절한다.
 * 키가 없으면 null 과 같다.
 */
export async function POST(
  request: Request,
  { params }: { params: Promise<{ code: string; connectorId: string }> },
) {
  const { code, connectorId } = await params;
  if (!AGENT_CODE_PATTERN.test(code) || !isConnectorId(connectorId)) {
    return invalidConnectionRequest();
  }
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return invalidConnectionRequest();
  // 키가 없으면 대기 시각을 보지 못한 것으로 보고 null 로 보낸다.
  const since = parsed.body.restartRequiredSince ?? null;
  if (
    since !== null &&
    (typeof since !== "string" || !Number.isFinite(Date.parse(since)))
  ) {
    return invalidConnectionRequest();
  }
  return connectionResponse(
    await connectorCall<AgentConnectionView>(
      `/api/v1/admin/agents/${code}/connections/${connectorId}/confirm`,
      "agentConnection",
      { method: "POST", body: { restartRequiredSince: since } },
    ),
  );
}
