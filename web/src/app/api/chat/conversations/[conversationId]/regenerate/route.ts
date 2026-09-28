import { NextResponse } from "next/server";
import { requestControlPlane } from "@/lib/control-plane";
import { isConversationId } from "@/lib/conversation-id";

type RouteContext = {
  params: Promise<{ conversationId: string }>;
};

/** 마지막 답을 다시 만드는 SSE 연결을 Control Plane 에서 그대로 전달한다. */
export async function POST(_request: Request, context: RouteContext) {
  const { conversationId } = await context.params;
  if (!isConversationId(conversationId)) {
    return NextResponse.json(
      { code: "VALIDATION_FAILED", message: "대화 주소가 올바르지 않아요." },
      { status: 400 },
    );
  }

  const opened = await requestControlPlane(
    `/api/v1/chat/conversations/${conversationId}/regenerate/stream`,
    { method: "POST" },
  );
  if (!opened.ok) {
    return NextResponse.json(
      { code: opened.code, message: opened.message },
      { status: opened.status },
    );
  }

  const upstream = opened.response;
  if (!upstream.ok || !upstream.body) {
    const payload = await upstream.json().catch(() => ({
      code: "INTERNAL_ERROR",
      message: "응답 연결을 열지 못했어요.",
    }));
    return NextResponse.json(payload, { status: upstream.status });
  }

  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    },
  });
}
