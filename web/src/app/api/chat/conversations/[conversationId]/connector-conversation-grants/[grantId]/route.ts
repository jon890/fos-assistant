import {
  connectorActionCall,
  connectorActionResponse,
  invalidConnectorActionRequest,
  isGrantId,
} from "@/lib/connector-action-route";
import { isConversationId } from "@/lib/conversation-id";

type Context = { params: Promise<{ conversationId: string; grantId: string }> };

/** 그 대화의 허락을 거둔다. 그 뒤로는 그 동작을 부를 때마다 다시 묻는다. */
export async function DELETE(_: Request, { params }: Context) {
  const { conversationId, grantId } = await params;
  if (!isConversationId(conversationId) || !isGrantId(grantId))
    return invalidConnectorActionRequest();
  return connectorActionResponse(
    await connectorActionCall<null>(
      `/api/v1/chat/conversations/${conversationId}/connector-conversation-grants/${grantId}`,
      "none",
      { method: "DELETE" },
    ),
  );
}
