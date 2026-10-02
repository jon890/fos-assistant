import {
  connectorActionCall,
  connectorActionResponse,
  invalidConnectorActionRequest,
  isActionId,
} from "@/lib/connector-action-route";
import type { ConnectorAction } from "@/lib/connector-action";

type Context = { params: Promise<{ actionId: string }> };

export async function POST(_: Request, { params }: Context) {
  const { actionId } = await params;
  if (!isActionId(actionId)) return invalidConnectorActionRequest();
  return connectorActionResponse(
    await connectorActionCall<ConnectorAction>(
      `/api/v1/connector-actions/${actionId}/reject`,
      "action",
      { method: "POST" },
    ),
  );
}
