import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

export async function GET() {
  const result = await callControlPlane<unknown[]>("/api/v1/chat/conversations");
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}

/** 제목이 빈 대화를 만든다. 사진을 먼저 올리려면 대화 번호가 먼저 있어야 한다. */
export async function POST(request: Request) {
  const body = (await request.json()) as { agentCode?: string };

  const result = await callControlPlane<{ conversationId: number }>("/api/v1/chat/conversations", {
    method: "POST",
    body: { agentCode: body.agentCode ?? null },
  });
  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
