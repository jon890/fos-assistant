import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { isConversationId } from "@/lib/conversation-id";

type Context = { params: Promise<{ conversationId: string }> };

/** 그 대화의 답마다 그 실행이 본문을 받은 기억을 읽는다. 답 아래의 「참고한 기억」 줄은 이 응답으로만 그린다. */
export async function GET(_: Request, { params }: Context) {
  const { conversationId } = await params;
  if (!isConversationId(conversationId))
    return errorResponse(
      "VALIDATION_FAILED",
      "대화 번호가 올바르지 않아요.",
      400,
    );
  const result = await callControlPlane(
    `/api/v1/chat/conversations/${conversationId}/memory-uses`,
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
