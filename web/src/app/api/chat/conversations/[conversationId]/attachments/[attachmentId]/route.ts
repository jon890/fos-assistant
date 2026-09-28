import { NextResponse } from "next/server";
import { callControlPlane, forwardControlPlane } from "@/lib/control-plane";
import { isConversationId } from "@/lib/conversation-id";

type RouteContext = {
  params: Promise<{ conversationId: string; attachmentId: string }>;
};

function badId() {
  return NextResponse.json(
    { code: "VALIDATION_FAILED", message: "대화 주소나 첨부 파일 번호가 올바르지 않아요." },
    { status: 400 },
  );
}

/** 사진 본문을 그대로 흘려보낸다. 지워졌으면 Control Plane 이 410 을 준다. */
export async function GET(_request: Request, context: RouteContext) {
  const { conversationId, attachmentId } = await context.params;
  if (!isConversationId(conversationId) || !/^\d+$/.test(attachmentId)) return badId();

  const opened = await forwardControlPlane(
    `/api/v1/chat/conversations/${conversationId}/attachments/${attachmentId}`,
    { method: "GET" },
  );
  if (!opened.ok) {
    return NextResponse.json({ code: opened.code, message: opened.message }, { status: opened.status });
  }

  const upstream = opened.response;
  if (!upstream.ok || !upstream.body) {
    const payload = await upstream.json().catch(() => ({
      code: "INTERNAL_ERROR",
      message: "사진을 불러오지 못했어요.",
    }));
    return NextResponse.json(payload, { status: upstream.status });
  }

  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      "Content-Type": upstream.headers.get("content-type") ?? "application/octet-stream",
      "Cache-Control": upstream.headers.get("cache-control") ?? "private",
    },
  });
}

export async function DELETE(_request: Request, context: RouteContext) {
  const { conversationId, attachmentId } = await context.params;
  if (!isConversationId(conversationId) || !/^\d+$/.test(attachmentId)) return badId();

  const result = await callControlPlane(
    `/api/v1/chat/conversations/${conversationId}/attachments/${attachmentId}`,
    { method: "DELETE" },
  );
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return new NextResponse(null, { status: 204 });
}
