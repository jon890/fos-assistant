import { isConversationId } from "@/lib/conversation-id";
import { forwardPending, invalidPendingRequest } from "@/lib/pending-route";

type RouteContext = {
  params: Promise<{ conversationId: string }>;
};

/** 멈춰 둔 대기 메시지를 보내게 한다. 본문은 없다. */
export async function POST(_request: Request, context: RouteContext) {
  const { conversationId } = await context.params;
  if (!isConversationId(conversationId)) return invalidPendingRequest();
  return forwardPending(
    `/api/v1/chat/conversations/${conversationId}/pending/send`,
    { method: "POST" },
  );
}
