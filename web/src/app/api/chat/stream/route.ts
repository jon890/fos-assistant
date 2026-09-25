import { NextResponse } from "next/server";
import { requestControlPlane } from "@/lib/control-plane";

export async function POST(request: Request) {
  const body = (await request.json()) as {
    conversationId?: number;
    text?: string;
    agentCode?: string;
    attachmentIds?: number[];
  };
  if (!body.text || body.text.trim().length === 0) {
    return NextResponse.json(
      { code: "VALIDATION_FAILED", message: "보낼 내용을 입력해 주세요." },
      { status: 400 },
    );
  }

  const opened = await requestControlPlane("/api/v1/chat/messages/stream", {
    method: "POST",
    body: {
      conversationId: body.conversationId ?? null,
      text: body.text,
      agentCode: body.agentCode ?? null,
      attachmentIds: body.attachmentIds ?? [],
    },
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
      message: "스트림을 열지 못했습니다.",
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
