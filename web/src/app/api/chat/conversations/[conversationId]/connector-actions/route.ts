import {
  connectorActionCall,
  connectorActionResponse,
  invalidConnectorActionRequest,
} from "@/lib/connector-action-route";
import type { ConnectorAction } from "@/lib/connector-action";
import { isConversationId } from "@/lib/conversation-id";

type Context = { params: Promise<{ conversationId: string }> };

/** 그 대화의 승인 줄을 읽는다. 대화의 승인 카드는 이 응답으로만 그린다. */
export async function GET(_: Request, { params }: Context) {
  const { conversationId } = await params;
  if (!isConversationId(conversationId)) return invalidConnectorActionRequest();
  return connectorActionResponse(
    await connectorActionCall<ConnectorAction[]>(
      `/api/v1/chat/conversations/${conversationId}/connector-actions`,
      "actions",
    ),
  );
}
