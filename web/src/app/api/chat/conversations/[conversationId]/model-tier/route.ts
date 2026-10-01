import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { isConversationId } from "@/lib/conversation-id";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";

type RouteContext = { params: Promise<{ conversationId: string }> };

/** 대화가 단계 선택을 쓸지 profile 기본값으로 돌아갈지를 저장한다. */
export async function PUT(request: Request, context: RouteContext) {
  const { conversationId } = await context.params;
  if (!isConversationId(conversationId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "대화 주소가 올바르지 않아요.",
      400,
    );
  }
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const result = await callControlPlane<unknown>(
    `/api/v1/chat/conversations/${conversationId}/model-tier`,
    { method: "PUT", body: parsed.body },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
