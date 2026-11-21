import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { isConversationId } from "@/lib/conversation-id";
import { errorResponse } from "@/lib/api-response";

type RouteContext = {
  params: Promise<{ conversationId: string }>;
};

/** 아직 답에 묶이지 않은 그림 경로를 저장한 순서대로 읽는다. 답이 끝나기 전에 그림을 그릴 때 쓴다. */
export async function GET(_request: Request, context: RouteContext) {
  const { conversationId } = await context.params;
  if (!isConversationId(conversationId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "대화 주소가 올바르지 않아요.",
      400,
    );
  }

  const result = await callControlPlane<unknown>(
    `/api/v1/chat/conversations/${conversationId}/live-images`,
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
