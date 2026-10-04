import { NextResponse } from "next/server";
import { requestControlPlane } from "@/lib/control-plane";
import { isConversationId } from "@/lib/conversation-id";
import { errorResponse } from "@/lib/api-response";

type RouteContext = {
  params: Promise<{ conversationId: string; deliveryId: string }>;
};

/** 결과 전달 묶음 번호다. 0 으로 시작하거나 Long 범위를 넘는 자리수는 받지 않는다. */
const DELIVERY_ID = /^[1-9][0-9]{0,18}$/;

/** 실패하거나 중지한 결과 전달을 저장된 결과만으로 다시 전달하는 SSE 연결을 Control Plane 에서 그대로 전달한다. */
export async function POST(_request: Request, context: RouteContext) {
  const { conversationId, deliveryId } = await context.params;
  if (!isConversationId(conversationId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "대화 주소가 올바르지 않아요.",
      400,
    );
  }
  if (!DELIVERY_ID.test(deliveryId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "다시 전할 결과를 찾지 못했어요.",
      400,
    );
  }

  const opened = await requestControlPlane(
    `/api/v1/chat/conversations/${conversationId}/deliveries/${deliveryId}/retry/stream`,
    { method: "POST" },
  );
  if (!opened.ok) {
    return errorResponse(opened.code, opened.message, opened.status);
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
