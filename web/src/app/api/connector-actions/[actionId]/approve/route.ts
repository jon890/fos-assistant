import {
  connectorActionCall,
  connectorActionResponse,
  invalidConnectorActionRequest,
  isActionId,
} from "@/lib/connector-action-route";
import {
  GRANT_PERIODS,
  type ConnectorAction,
  type GrantPeriod,
} from "@/lib/connector-action";
import { readJsonBody } from "@/lib/json-body";

type Context = { params: Promise<{ actionId: string }> };

/**
 * 승인한다. `grant` 는 묻지 않을 기간이고 `null` 이면 이번 한 번만 승인한다. `conversationGrant` 가 참이면 이 대화에서
 * 계속 허용한다. 둘을 함께 보내면 입력 오류다.
 */
export async function POST(request: Request, { params }: Context) {
  const { actionId } = await params;
  if (!isActionId(actionId)) return invalidConnectorActionRequest();
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return invalidConnectorActionRequest();
  const grant = parsed.body.grant ?? null;
  if (grant !== null && !GRANT_PERIODS.includes(grant as GrantPeriod))
    return invalidConnectorActionRequest();
  const conversationGrant = parsed.body.conversationGrant ?? false;
  if (
    typeof conversationGrant !== "boolean" ||
    (conversationGrant && grant !== null)
  )
    return invalidConnectorActionRequest();
  return connectorActionResponse(
    await connectorActionCall<ConnectorAction>(
      `/api/v1/connector-actions/${actionId}/approve`,
      "action",
      // 대화 허락이 없으면 옛 본문 그대로 보낸다.
      {
        method: "POST",
        body: conversationGrant ? { grant, conversationGrant } : { grant },
      },
    ),
  );
}
