import { NextResponse } from "next/server";
import { requestControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/**
 * 로그인한 사용자의 알림 사건을 받는다. 새 알림과 읽음이 읽지 않은 수와 함께 온다.
 *
 * <p>이 스트림은 끝나지 않는다. 브라우저가 끊으면 `request.signal` 로 Control Plane 쪽 연결도 끊는다.
 */
export async function GET(request: Request) {
  const opened = await requestControlPlane("/api/v1/notifications/events", {
    signal: request.signal,
  });
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
