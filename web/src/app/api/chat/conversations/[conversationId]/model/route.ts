import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { isConversationId } from "@/lib/conversation-id";

type RouteContext = {
  params: Promise<{ conversationId: string }>;
};

/** 대화에서 쓸 모델과 effort 를 바꾼다. 바뀐 대화 한 줄을 돌려준다. */
export async function PUT(request: Request, context: RouteContext) {
  const { conversationId } = await context.params;
  if (!isConversationId(conversationId)) {
    return NextResponse.json(
      { code: "VALIDATION_FAILED", message: "대화 주소가 올바르지 않아요." },
      { status: 400 },
    );
  }

  const result = await callControlPlane<unknown>(
    `/api/v1/chat/conversations/${conversationId}/model`,
    { method: "PUT", body: await request.json() },
  );
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
