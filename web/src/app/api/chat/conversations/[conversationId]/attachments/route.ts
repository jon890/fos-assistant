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
  // 역방향 프록시가 413 을 HTML 로 돌려주는 등 JSON 이 아닌 응답이 올 수 있다. 그때는 그대로 던지지
  // 않고 VALIDATION_FAILED 로 옮긴다.
  if (upstream.status === 413) {
    return NextResponse.json(
      { code: "VALIDATION_FAILED", message: "사진이 너무 큽니다." },
      { status: 400 },
    );
  }
  let payload: unknown = null;
  try {
    payload = text.length > 0 ? JSON.parse(text) : null;
  } catch {
    return NextResponse.json(
      { code: "VALIDATION_FAILED", message: "요청을 처리하지 못했습니다." },
      { status: 400 },
    );
  }
  return NextResponse.json(payload, { status: upstream.status });
}
