import {
  connectorActionCall,
  connectorActionResponse,
  invalidConnectorActionRequest,
} from "@/lib/connector-action-route";
import type { ConversationGrant } from "@/lib/connector-action";
import { isConversationId } from "@/lib/conversation-id";

type Context = { params: Promise<{ conversationId: string }> };

/** 그 대화에서 계속 허용 중인 동작을 읽는다. */
export async function GET(_: Request, { params }: Context) {
  const { conversationId } = await params;
  if (!isConversationId(conversationId)) return invalidConnectorActionRequest();
  return connectorActionResponse(
    await connectorActionCall<ConversationGrant[]>(
      `/api/v1/chat/conversations/${conversationId}/connector-conversation-grants`,
      "conversationGrants",
    ),
  );
}
