import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { isConversationId } from "@/lib/conversation-id";
import { errorResponse } from "@/lib/api-response";

type RouteContext = {
  params: Promise<{ conversationId: string }>;
};

/** 대화에서 쓸 모델과 effort 를 바꾼다. 바뀐 대화 한 줄을 돌려준다. */
export async function PUT(request: Request, context: RouteContext) {
  const { conversationId } = await context.params;
  if (!isConversationId(conversationId)) {
    return errorResponse("VALIDATION_FAILED", "대화 주소가 올바르지 않아요.", 400);
  }

  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane<unknown>(
    `/api/v1/chat/conversations/${conversationId}/model`,
    { method: "PUT", body: parsed.body },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
