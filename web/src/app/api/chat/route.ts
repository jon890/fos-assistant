import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

type SendMessageResponse = {
  conversationId: number;
  executionId: number;
  assistantText: string;
};

export async function POST(request: Request) {
  const body = (await request.json()) as { conversationId?: number; text?: string };
  if (!body.text || body.text.trim().length === 0) {
    return NextResponse.json({ code: "VALIDATION_FAILED", message: "보낼 내용을 입력해 주세요." }, { status: 400 });
  }

  const result = await callControlPlane<SendMessageResponse>("/api/v1/chat/messages", {
    method: "POST",
    body: { conversationId: body.conversationId ?? null, text: body.text },
  });

  if (!result.ok) {
    return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  }
  return NextResponse.json(result.data);
}
