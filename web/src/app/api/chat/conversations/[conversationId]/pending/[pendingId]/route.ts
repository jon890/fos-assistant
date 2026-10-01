import { isConversationId } from "@/lib/conversation-id";
import { forwardPending, invalidPendingRequest } from "@/lib/pending-route";

type RouteContext = {
  params: Promise<{ conversationId: string; pendingId: string }>;
};

/** 대기 메시지 하나를 취소한다. 이미 보내졌으면 Control Plane 이 404 를 준다. */
export async function DELETE(_request: Request, context: RouteContext) {
  const { conversationId, pendingId } = await context.params;
  if (!isConversationId(conversationId) || !/^[1-9]\d*$/.test(pendingId)) {
    return invalidPendingRequest();
  }
  return forwardPending(
    `/api/v1/chat/conversations/${conversationId}/pending/${pendingId}`,
    { method: "DELETE" },
  );
}
