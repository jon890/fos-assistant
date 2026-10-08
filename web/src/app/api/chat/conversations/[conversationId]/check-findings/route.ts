import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { isConversationId } from "@/lib/conversation-id";

type Context = { params: Promise<{ conversationId: string }> };

/** 점검 대화에서 「새로 알릴 것」 으로 그린 발견과 지금 반응을 읽는다. */
export async function GET(_: Request, { params }: Context) {
  const { conversationId } = await params;
  if (!isConversationId(conversationId))
    return errorResponse(
      "VALIDATION_FAILED",
      "대화 번호가 올바르지 않아요.",
      400,
    );
  const result = await callControlPlane(
    `/api/v1/chat/conversations/${conversationId}/check-findings`,
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
