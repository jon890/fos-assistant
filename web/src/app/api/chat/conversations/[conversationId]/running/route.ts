import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { isConversationId } from "@/lib/conversation-id";

type RouteContext = {
  params: Promise<{ conversationId: string }>;
};

/** 대화에 지금 도는 turn 이 있는지 묻는다. 같은 대화를 연 다른 창이 쓴다. */
export async function GET(_request: Request, context: RouteContext) {
  const { conversationId } = await context.params;
  if (!isConversationId(conversationId)) {
    return NextResponse.json(
      { code: "VALIDATION_FAILED", message: "대화 주소가 올바르지 않습니다." },
      { status: 400 },
    );
  }

  const result = await callControlPlane<unknown>(
    `/api/v1/chat/conversations/${conversationId}/running`,
  );
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
