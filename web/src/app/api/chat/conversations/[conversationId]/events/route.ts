import { NextResponse } from "next/server";
import { requestControlPlane } from "@/lib/control-plane";
import { isConversationId } from "@/lib/conversation-id";

type RouteContext = {
  params: Promise<{ conversationId: string }>;
};

/**
 * 열린 대화 창이 요청 없이 도는 turn 의 사건을 받는다. 위임 결과로 열린 자동 turn 만 온다.
 *
 * <p>이 스트림은 끝나지 않는다. 브라우저가 끊으면 `request.signal` 로 Control Plane 쪽 연결도 끊는다.
 */
export async function GET(request: Request, context: RouteContext) {
  const { conversationId } = await context.params;
  if (!isConversationId(conversationId)) {
    return NextResponse.json(
      { code: "VALIDATION_FAILED", message: "대화 주소가 올바르지 않아요." },
      { status: 400 },
    );
  }

  const opened = await requestControlPlane(`/api/v1/chat/conversations/${conversationId}/events`, {
    signal: request.signal,
  });
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
