import { NextResponse } from "next/server";
import { forwardControlPlane } from "@/lib/control-plane";

type RouteContext = {
  params: Promise<{ conversationId: string }>;
};

/** 사진을 올린다. multipart 본문을 읽어 다시 만들지 않고 그대로 흘려보낸다. */
export async function POST(request: Request, context: RouteContext) {
  const { conversationId } = await context.params;
  if (!/^\d+$/.test(conversationId)) {
    return NextResponse.json(
      { code: "VALIDATION_FAILED", message: "대화 번호가 올바르지 않습니다." },
      { status: 400 },
    );
  }

  const opened = await forwardControlPlane(
    `/api/v1/chat/conversations/${conversationId}/attachments`,
    {
      method: "POST",
      body: request.body,
      contentType: request.headers.get("content-type"),
    },
  );
  if (!opened.ok) {
    return NextResponse.json({ code: opened.code, message: opened.message }, { status: opened.status });
  }

  const upstream = opened.response;
  const text = await upstream.text();
  const payload = text.length > 0 ? JSON.parse(text) : null;
  return NextResponse.json(payload, { status: upstream.status });
}
