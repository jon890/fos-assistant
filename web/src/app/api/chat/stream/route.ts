import { NextResponse } from "next/server";
import { requestControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { isConversationId } from "@/lib/conversation-id";

export async function POST(request: Request) {
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const body = parsed.body as {
    conversationId?: string;
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
  if (body.conversationId != null && !isConversationId(body.conversationId)) {
    return NextResponse.json(
      { code: "VALIDATION_FAILED", message: "대화 주소가 올바르지 않아요." },
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
