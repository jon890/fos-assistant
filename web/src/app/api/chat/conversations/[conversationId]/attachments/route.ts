import { isConversationId } from "@/lib/conversation-id";
import { errorResponse } from "@/lib/api-response";
import { forwardMultipart } from "@/lib/forward-multipart";

type RouteContext = {
  params: Promise<{ conversationId: string }>;
};

/** 사진을 올린다. multipart 본문을 읽어 다시 만들지 않고 그대로 흘려보낸다. */
export async function POST(request: Request, context: RouteContext) {
  const { conversationId } = await context.params;
  if (!isConversationId(conversationId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "대화 주소가 올바르지 않아요.",
      400,
    );
  }

  return forwardMultipart(
    `/api/v1/chat/conversations/${conversationId}/attachments`,
    request,
    "사진이 너무 커요.",
  );
}
